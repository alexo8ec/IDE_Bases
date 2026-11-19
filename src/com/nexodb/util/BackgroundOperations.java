package com.nexodb.util;

import javax.swing.SwingUtilities;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide, data-free activity registry. It coordinates expensive work without
 * retaining SQL, parameters, result values or credentials.
 */
public final class BackgroundOperations {
    public enum Kind { QUERY, COUNT, EXPORT, REPORT, MIGRATION, RELOAD, PREFETCH, CONNECTION }
    public enum Priority { INTERACTIVE, BACKGROUND, SPECULATIVE }
    public enum State { QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED, TIMED_OUT }
    public record Snapshot(long id,Kind kind,Priority priority,String label,String connection,State state,int queuePosition,Instant queuedAt,
                           Instant startedAt,Instant finishedAt,int progress,String detail,
                           boolean canCancel,boolean canRetry,boolean timedOut) {
        public long elapsedMillis(){Instant end=finishedAt==null?Instant.now():finishedAt;Instant start=startedAt==null?queuedAt:startedAt;return Math.max(0,Duration.between(start,end).toMillis());}
        public boolean active(){return state==State.QUEUED||state==State.RUNNING;}
    }
    public interface Listener { void changed(); }

    public static final class Operation implements AutoCloseable {
        private final Entry entry;
        private Operation(Entry entry){this.entry=entry;}
        public long id(){return entry.id;}
        public void awaitTurn()throws InterruptedException{
            synchronized(LOCK){
                while(entry.state==State.QUEUED&&!canStart(entry)){LOCK.wait(250);}
                if(entry.state!=State.QUEUED)throw new CancellationException("La operación fue cancelada antes de comenzar.");
                running++;entry.slot=true;entry.state=State.RUNNING;entry.startedAt=Instant.now();scheduleTimeout(entry);changed();
            }
        }
        public void progress(int percent,String detail){synchronized(LOCK){if(!entry.active())return;entry.progress=Math.max(-1,Math.min(100,percent));entry.detail=safe(detail);changed();}}
        public void complete(String detail){finish(entry,State.COMPLETED,safe(detail));}
        public void cancelled(String detail){finish(entry,State.CANCELLED,safe(detail));}
        public void fail(Throwable error){finish(entry,State.FAILED,safeError(error));}
        public void fail(String detail){finish(entry,State.FAILED,safe(detail));}
        public void requestCancel(){cancel(entry,false);}
        public Snapshot snapshot(){synchronized(LOCK){return entry.snapshot();}}
        @Override public void close(){synchronized(LOCK){if(entry.active())complete("");}}
    }

    private static final class Entry {
        final long id;final Kind kind;final Priority priority;final String label,connection;final Instant queuedAt=Instant.now();final Runnable cancel,retry;final int timeoutSeconds;
        State state=State.QUEUED;Instant startedAt,finishedAt;int progress=-1;String detail="";boolean slot,timedOut;ScheduledFuture<?> timeout;
        Entry(long id,Kind kind,String label,String connection,Runnable cancel,Runnable retry,int timeoutSeconds){this.id=id;this.kind=kind;this.priority=priority(kind);this.label=safe(label);this.connection=safe(connection);this.cancel=cancel;this.retry=retry;this.timeoutSeconds=timeoutSeconds;}
        boolean active(){return state==State.QUEUED||state==State.RUNNING;}
        Snapshot snapshot(){return new Snapshot(id,kind,priority,label,connection,state,queuePosition(this),queuedAt,startedAt,finishedAt,progress,detail,active()&&cancel!=null,retry!=null&&!active(),timedOut);}
    }
    private static final class ConnectionStatus {final String name;Entry recovery;ConnectionStatus(String name){this.name=safeLabel(name);}}

    private static final Object LOCK=new Object();
    private static final AtomicLong IDS=new AtomicLong();
    private static final LinkedHashMap<Long,Entry> ENTRIES=new LinkedHashMap<>();
    private static final IdentityHashMap<Connection,ConnectionStatus> CONNECTIONS=new IdentityHashMap<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS=new CopyOnWriteArrayList<>();
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r->{Thread thread=new Thread(r,"adisdb-operation-timeouts");thread.setDaemon(true);return thread;});
    private static volatile int maxConcurrent=setting("background.maxConcurrent",4,1,8);
    private static volatile int maxPerConnection=setting("background.maxPerConnection",2,1,4);
    private static volatile int timeoutSeconds=setting("background.timeoutSeconds",120,5,3600);
    private static volatile boolean adaptive=Boolean.parseBoolean(AppSettings.get("background.adaptive","true"));
    private static volatile int effectiveConcurrent=maxConcurrent;
    private static int successfulSinceAdjustment;
    private static int running;

    private BackgroundOperations(){}

    public static Operation create(Kind kind,String label,String connection,Runnable cancel,Runnable retry){return create(kind,label,connection,cancel,retry,timeoutSeconds);}
    public static Operation create(Kind kind,String label,String connection,Runnable cancel,Runnable retry,int timeout){
        Entry entry=new Entry(IDS.incrementAndGet(),Objects.requireNonNull(kind),label,connection,cancel,retry,Math.max(1,timeout));
        List<Entry> obsolete=List.of();synchronized(LOCK){if(entry.priority==Priority.INTERACTIVE)obsolete=ENTRIES.values().stream().filter(candidate->candidate.kind==Kind.PREFETCH&&candidate.active()&&candidate.connection.equals(entry.connection)).toList();ENTRIES.put(entry.id,entry);trim();changed();}
        obsolete.forEach(candidate->cancel(candidate,false));return new Operation(entry);
    }
    public static List<Snapshot> snapshots(){synchronized(LOCK){return ENTRIES.values().stream().map(Entry::snapshot).sorted(Comparator.comparing((Snapshot snapshot)->!snapshot.active()).thenComparing(Snapshot::queuedAt,Comparator.reverseOrder())).toList();}}
    public static Snapshot snapshot(long id){synchronized(LOCK){Entry entry=ENTRIES.get(id);return entry==null?null:entry.snapshot();}}
    public static void cancel(long id){synchronized(LOCK){Entry entry=ENTRIES.get(id);if(entry!=null)cancel(entry,false);}}
    public static void retry(long id){Runnable retry;synchronized(LOCK){Entry entry=ENTRIES.get(id);retry=entry!=null&&!entry.active()?entry.retry:null;}if(retry!=null)SwingUtilities.invokeLater(retry);}
    public static void clearFinished(){synchronized(LOCK){ENTRIES.values().removeIf(entry->!entry.active());changed();}}
    public static int activeCount(){synchronized(LOCK){return (int)ENTRIES.values().stream().filter(Entry::active).count();}}
    public static int queuedCount(){synchronized(LOCK){return (int)ENTRIES.values().stream().filter(entry->entry.state==State.QUEUED).count();}}
    public static int maxConcurrent(){return maxConcurrent;}
    public static int effectiveMaxConcurrent(){return effectiveConcurrent;}
    public static int maxPerConnection(){return maxPerConnection;}
    public static int timeoutSeconds(){return timeoutSeconds;}
    public static boolean adaptiveEnabled(){return adaptive;}
    public static void configure(int concurrent,int timeout){configure(concurrent,maxPerConnection,timeout);}
    public static void configure(int concurrent,int perConnection,int timeout){configure(concurrent,perConnection,timeout,adaptive);}
    public static void configure(int concurrent,int perConnection,int timeout,boolean adapt){maxConcurrent=Math.max(1,Math.min(8,concurrent));maxPerConnection=Math.max(1,Math.min(4,perConnection));timeoutSeconds=Math.max(5,Math.min(3600,timeout));adaptive=adapt;effectiveConcurrent=maxConcurrent;successfulSinceAdjustment=0;AppSettings.put("background.maxConcurrent",Integer.toString(maxConcurrent));AppSettings.put("background.maxPerConnection",Integer.toString(maxPerConnection));AppSettings.put("background.timeoutSeconds",Integer.toString(timeoutSeconds));AppSettings.put("background.adaptive",Boolean.toString(adaptive));synchronized(LOCK){LOCK.notifyAll();changed();}}
    public static void addListener(Listener listener){if(listener!=null)LISTENERS.addIfAbsent(listener);}
    public static void removeListener(Listener listener){LISTENERS.remove(listener);}

    public static void registerConnection(Connection connection,String name){if(connection==null)return;synchronized(LOCK){CONNECTIONS.put(connection,new ConnectionStatus(name));}}
    public static void unregisterConnection(Connection connection){if(connection==null)return;synchronized(LOCK){CONNECTIONS.remove(connection);}}
    public static void connectionRecovering(Connection connection){
        synchronized(LOCK){ConnectionStatus status=CONNECTIONS.get(connection);if(status==null)return;if(status.recovery!=null&&status.recovery.active())return;Entry entry=new Entry(IDS.incrementAndGet(),Kind.CONNECTION,"Recuperación de conexión",status.name,null,null,30);entry.state=State.RUNNING;entry.startedAt=Instant.now();entry.detail="Verificando y restableciendo la conexión…";status.recovery=entry;ENTRIES.put(entry.id,entry);trim();changed();}
    }
    public static void connectionRecovered(Connection connection){synchronized(LOCK){ConnectionStatus status=CONNECTIONS.get(connection);if(status!=null&&status.recovery!=null){finish(status.recovery,State.COMPLETED,"Conexión restablecida");status.recovery=null;}}}
    public static void connectionRecoveryFailed(Connection connection){synchronized(LOCK){ConnectionStatus status=CONNECTIONS.get(connection);if(status!=null&&status.recovery!=null){finish(status.recovery,State.FAILED,"No se pudo restablecer la conexión");status.recovery=null;}}}

    private static void scheduleTimeout(Entry entry){if(entry.timeoutSeconds<=0)return;entry.timeout=TIMER.schedule(()->{synchronized(LOCK){if(!entry.active())return;entry.timedOut=true;entry.detail="Se alcanzó el tiempo máximo configurado";cancel(entry,true);}},entry.timeoutSeconds,TimeUnit.SECONDS);}
    private static void cancel(Entry entry,boolean timeout){if(!entry.active())return;Runnable action=entry.cancel;finish(entry,timeout?State.TIMED_OUT:State.CANCELLED,timeout?"Se alcanzó el tiempo máximo configurado":"Cancelada por el usuario");if(action!=null)try{action.run();}catch(RuntimeException ignored){}}
    private static void finish(Entry entry,State state,String detail){synchronized(LOCK){if(!entry.active())return;if(entry.timeout!=null)entry.timeout.cancel(false);entry.timeout=null;entry.state=state;entry.finishedAt=Instant.now();entry.progress=state==State.COMPLETED?100:entry.progress;entry.detail=safe(detail);if(entry.slot){entry.slot=false;running=Math.max(0,running-1);}adapt(entry,state);LOCK.notifyAll();trim();changed();}}
    private static boolean canStart(Entry entry){if(running>=effectiveConcurrent||runningFor(entry.connection)>=maxPerConnection)return false;return ENTRIES.values().stream().filter(candidate->candidate.state==State.QUEUED&&runningFor(candidate.connection)<maxPerConnection).min(Comparator.comparingInt((Entry candidate)->candidate.priority.ordinal()).thenComparingLong(candidate->candidate.id)).orElse(null)==entry;}
    private static void adapt(Entry entry,State state){
        if(!adaptive||entry.priority==Priority.SPECULATIVE)return;long free=memoryHeadroom();
        if(free<96L*1024*1024){effectiveConcurrent=1;successfulSinceAdjustment=0;return;}
        if(state==State.FAILED||state==State.TIMED_OUT){effectiveConcurrent=Math.max(1,effectiveConcurrent-1);successfulSinceAdjustment=0;return;}
        if(state==State.COMPLETED&&entry.startedAt!=null&&Duration.between(entry.startedAt,entry.finishedAt).toMillis()<3_000){successfulSinceAdjustment++;if(successfulSinceAdjustment>=5&&free>256L*1024*1024){effectiveConcurrent=Math.min(maxConcurrent,effectiveConcurrent+1);successfulSinceAdjustment=0;}}
    }
    private static long memoryHeadroom(){Runtime runtime=Runtime.getRuntime();return Math.max(0,runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory()));}
    private static long runningFor(String connection){return ENTRIES.values().stream().filter(candidate->candidate.slot&&candidate.state==State.RUNNING&&candidate.connection.equals(connection)).count();}
    private static int queuePosition(Entry entry){if(entry.state!=State.QUEUED)return 0;List<Entry> queue=ENTRIES.values().stream().filter(candidate->candidate.state==State.QUEUED).sorted(Comparator.comparingInt((Entry candidate)->candidate.priority.ordinal()).thenComparingLong(candidate->candidate.id)).toList();return queue.indexOf(entry)+1;}
    private static Priority priority(Kind kind){return switch(kind){case QUERY,RELOAD,CONNECTION->Priority.INTERACTIVE;case COUNT,EXPORT,REPORT,MIGRATION->Priority.BACKGROUND;case PREFETCH->Priority.SPECULATIVE;};}
    private static void trim(){int terminal=(int)ENTRIES.values().stream().filter(entry->!entry.active()).count();if(terminal<=100)return;var iterator=ENTRIES.entrySet().iterator();while(iterator.hasNext()&&terminal>100){Entry entry=iterator.next().getValue();if(!entry.active()){iterator.remove();terminal--;}}}
    private static void changed(){for(Listener listener:LISTENERS)SwingUtilities.invokeLater(listener::changed);}
    private static int setting(String key,int fallback,int min,int max){try{return Math.max(min,Math.min(max,Integer.parseInt(AppSettings.get(key,Integer.toString(fallback)))));}catch(RuntimeException error){return fallback;}}
    private static String safeLabel(String text){String value=Objects.toString(text,"").replaceAll("[\\r\\n\\t]+"," ").trim();return value.length()>120?value.substring(0,120)+"…":value;}
    private static String safe(String text){String value=safeLabel(text).replaceAll("(?i)jdbc:[^ ]+","[dirección oculta]").replaceAll("(?i)(password|contraseña|token|secret)[^ ]*","[dato oculto]");return value;}
    private static String safeError(Throwable error){
        Throwable root=error;while(root!=null&&root.getCause()!=null)root=root.getCause();
        if(root instanceof CancellationException||root instanceof InterruptedException)return "Operación cancelada";
        if(root instanceof SQLException sql){String state=Objects.toString(sql.getSQLState(),"");if(state.startsWith("08"))return "Conexión interrumpida · reintento seguro disponible";return state.isBlank()?"El servidor rechazó la operación":"Error del servidor · SQLSTATE "+safe(state);}
        return root==null?"Error desconocido":"Error de "+safeLabel(root.getClass().getSimpleName());
    }
}
