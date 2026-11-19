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
    public enum Kind { QUERY, COUNT, EXPORT, REPORT, RELOAD, PREFETCH, CONNECTION }
    public enum State { QUEUED, RUNNING, COMPLETED, CANCELLED, FAILED, TIMED_OUT }
    public record Snapshot(long id,Kind kind,String label,String connection,State state,Instant queuedAt,
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
                while(entry.state==State.QUEUED&&running>=maxConcurrent){LOCK.wait(250);}
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
        final long id;final Kind kind;final String label,connection;final Instant queuedAt=Instant.now();final Runnable cancel,retry;final int timeoutSeconds;
        State state=State.QUEUED;Instant startedAt,finishedAt;int progress=-1;String detail="";boolean slot,timedOut;ScheduledFuture<?> timeout;
        Entry(long id,Kind kind,String label,String connection,Runnable cancel,Runnable retry,int timeoutSeconds){this.id=id;this.kind=kind;this.label=safe(label);this.connection=safe(connection);this.cancel=cancel;this.retry=retry;this.timeoutSeconds=timeoutSeconds;}
        boolean active(){return state==State.QUEUED||state==State.RUNNING;}
        Snapshot snapshot(){return new Snapshot(id,kind,label,connection,state,queuedAt,startedAt,finishedAt,progress,detail,active()&&cancel!=null,retry!=null&&!active(),timedOut);}
    }
    private static final class ConnectionStatus {final String name;Entry recovery;ConnectionStatus(String name){this.name=safeLabel(name);}}

    private static final Object LOCK=new Object();
    private static final AtomicLong IDS=new AtomicLong();
    private static final LinkedHashMap<Long,Entry> ENTRIES=new LinkedHashMap<>();
    private static final IdentityHashMap<Connection,ConnectionStatus> CONNECTIONS=new IdentityHashMap<>();
    private static final CopyOnWriteArrayList<Listener> LISTENERS=new CopyOnWriteArrayList<>();
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(r->{Thread thread=new Thread(r,"adisdb-operation-timeouts");thread.setDaemon(true);return thread;});
    private static volatile int maxConcurrent=setting("background.maxConcurrent",4,1,8);
    private static volatile int timeoutSeconds=setting("background.timeoutSeconds",120,5,3600);
    private static int running;

    private BackgroundOperations(){}

    public static Operation create(Kind kind,String label,String connection,Runnable cancel,Runnable retry){return create(kind,label,connection,cancel,retry,timeoutSeconds);}
    public static Operation create(Kind kind,String label,String connection,Runnable cancel,Runnable retry,int timeout){
        Entry entry=new Entry(IDS.incrementAndGet(),Objects.requireNonNull(kind),label,connection,cancel,retry,Math.max(1,timeout));
        synchronized(LOCK){ENTRIES.put(entry.id,entry);trim();changed();}return new Operation(entry);
    }
    public static List<Snapshot> snapshots(){synchronized(LOCK){return ENTRIES.values().stream().map(Entry::snapshot).sorted(Comparator.comparing((Snapshot snapshot)->!snapshot.active()).thenComparing(Snapshot::queuedAt,Comparator.reverseOrder())).toList();}}
    public static Snapshot snapshot(long id){synchronized(LOCK){Entry entry=ENTRIES.get(id);return entry==null?null:entry.snapshot();}}
    public static void cancel(long id){synchronized(LOCK){Entry entry=ENTRIES.get(id);if(entry!=null)cancel(entry,false);}}
    public static void retry(long id){Runnable retry;synchronized(LOCK){Entry entry=ENTRIES.get(id);retry=entry!=null&&!entry.active()?entry.retry:null;}if(retry!=null)SwingUtilities.invokeLater(retry);}
    public static void clearFinished(){synchronized(LOCK){ENTRIES.values().removeIf(entry->!entry.active());changed();}}
    public static int activeCount(){synchronized(LOCK){return (int)ENTRIES.values().stream().filter(Entry::active).count();}}
    public static int queuedCount(){synchronized(LOCK){return (int)ENTRIES.values().stream().filter(entry->entry.state==State.QUEUED).count();}}
    public static int maxConcurrent(){return maxConcurrent;}
    public static int timeoutSeconds(){return timeoutSeconds;}
    public static void configure(int concurrent,int timeout){maxConcurrent=Math.max(1,Math.min(8,concurrent));timeoutSeconds=Math.max(5,Math.min(3600,timeout));AppSettings.put("background.maxConcurrent",Integer.toString(maxConcurrent));AppSettings.put("background.timeoutSeconds",Integer.toString(timeoutSeconds));synchronized(LOCK){LOCK.notifyAll();changed();}}
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
    private static void finish(Entry entry,State state,String detail){synchronized(LOCK){if(!entry.active())return;if(entry.timeout!=null)entry.timeout.cancel(false);entry.timeout=null;entry.state=state;entry.finishedAt=Instant.now();entry.progress=state==State.COMPLETED?100:entry.progress;entry.detail=safe(detail);if(entry.slot){entry.slot=false;running=Math.max(0,running-1);LOCK.notifyAll();}trim();changed();}}
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
