package com.nexodb.db;

import com.nexodb.model.*;
import com.nexodb.util.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Local application scheduler. Jobs are persisted; credentials are retained only in session memory. */
public final class ScheduledTasks implements AutoCloseable {
    public enum Kind { BACKUP, REPORT, SQL }
    public record Task(String id,String name,String profile,String database,Kind kind,String script,String hash,String output,int minutes,Instant next,boolean enabled,int retention){
        public Task(String id,String name,String profile,String database,Kind kind,String script,String hash,String output,int minutes,Instant next,boolean enabled){this(id,name,profile,database,kind,script,hash,output,minutes,next,enabled,7);}
    }
    public record State(boolean running,String status,String output,Instant lastRun){}
    @FunctionalInterface public interface Passwords {char[] load(String profile)throws Exception;}
    private final Map<String,Task> tasks=new LinkedHashMap<>();private final Map<String,State> states=new HashMap<>();private final Map<String,char[]> passwords=new HashMap<>();private final Map<String,SqlTransferControl> running=new HashMap<>();
    private final Set<String> queued=new HashSet<>();private final Path file;private final Function<String,ConnectionProfile> profiles;private final Passwords stored;private final Consumer<String> notify;
    private final Map<String,String> signatures=new HashMap<>();
    private volatile BooleanSupplier licenseCheck=()->true;
    public void setLicenseCheck(BooleanSupplier check){licenseCheck=check;}
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"nexodb-scheduled-jobs");thread.setDaemon(true);return thread;});private boolean closed;
    public ScheduledTasks(Path file,Function<String,ConnectionProfile> profiles,Passwords stored,Consumer<String> notify,boolean start){this.file=file;this.profiles=profiles;this.stored=stored;this.notify=notify;load();if(start)executor.scheduleWithFixedDelay(this::tick,10,10,TimeUnit.SECONDS);}
    private synchronized void load(){if(file==null)return;try{Properties p=AtomicProperties.read(file);int count=Math.min(200,Integer.parseInt(p.getProperty("count","0")));for(int i=0;i<count;i++){String k=i+".";Task task=new Task(p.getProperty(k+"id"),p.getProperty(k+"name"),p.getProperty(k+"profile"),p.getProperty(k+"database"),Kind.valueOf(p.getProperty(k+"kind")),p.getProperty(k+"script",""),p.getProperty(k+"hash",""),p.getProperty(k+"output"),Integer.parseInt(p.getProperty(k+"minutes","0")),Instant.parse(p.getProperty(k+"next")),Boolean.parseBoolean(p.getProperty(k+"enabled","false")),Integer.parseInt(p.getProperty(k+"retention","7")));tasks.put(task.id(),task);signatures.put(task.id(),p.getProperty(k+"connectionHash",""));states.put(task.id(),new State(false,p.getProperty(k+"status",""),p.getProperty(k+"lastOutput",""),p.containsKey(k+"lastRun")?Instant.parse(p.getProperty(k+"lastRun")):null));}}catch(Exception error){notify.accept("No se pudieron cargar las tareas: "+error.getMessage());}}
    private synchronized void save()throws IOException{if(file==null)return;Properties p=new Properties();p.setProperty("count",""+tasks.size());int i=0;for(Task t:tasks.values()){String k=(i++)+".";p.setProperty(k+"id",t.id());p.setProperty(k+"name",t.name());p.setProperty(k+"profile",t.profile());p.setProperty(k+"database",t.database());p.setProperty(k+"kind",t.kind().name());p.setProperty(k+"script",t.script());p.setProperty(k+"hash",t.hash());p.setProperty(k+"output",t.output());p.setProperty(k+"minutes",""+t.minutes());p.setProperty(k+"next",t.next().toString());p.setProperty(k+"enabled",""+t.enabled());p.setProperty(k+"retention",""+t.retention());p.setProperty(k+"connectionHash",signatures.getOrDefault(t.id(),""));State state=states.get(t.id());if(state!=null){p.setProperty(k+"status",state.status());p.setProperty(k+"lastOutput",state.output());if(state.lastRun()!=null)p.setProperty(k+"lastRun",state.lastRun().toString());}}AtomicProperties.write(file,p);}
    public synchronized List<Task> list(){return List.copyOf(tasks.values());}
    public synchronized State state(String id){return states.getOrDefault(id,new State(false,"","",null));}
    public synchronized List<Task> runningTasks(){return tasks.values().stream().filter(task->state(task.id()).running()).toList();}
    public synchronized void credentials(String profile,char[] value){char[] previous=passwords.put(profile,value.clone());if(previous!=null)Arrays.fill(previous,'\0');}
    /** Returns a disposable copy for a short-lived connection such as catalog discovery. */
    public char[] credentials(String profile)throws Exception{return password(profile);}
    public synchronized void put(Task task)throws IOException{if(closed)throw new IllegalStateException("Scheduler closed");if(queued.contains(task.id())||running.containsKey(task.id()))throw new IllegalStateException("La tarea está en ejecución.");if(task.name().isBlank()||task.database().isBlank()||task.minutes()<0||task.minutes()>525600||task.retention()<0||task.retention()>1000)throw new IllegalArgumentException("Revisa nombre, base, frecuencia y retención de la tarea.");if(tasks.size()>=200&&!tasks.containsKey(task.id()))throw new IllegalArgumentException("Máximo de 200 tareas.");try(StoreLease lease=requireStoreLease()){ConnectionProfile profile=profiles.apply(task.profile());if(profile==null)throw new IllegalArgumentException("La conexión no existe.");signatures.put(task.id(),signature(profile));tasks.put(task.id(),task);State previous=states.get(task.id());states.put(task.id(),new State(false,"Configuración y credencial verificadas; pendiente de ejecución.",previous==null?"":previous.output(),previous==null?null:previous.lastRun()));save();}}
    public synchronized void enable(String id,boolean enabled)throws IOException{Task t=tasks.get(id);if(t!=null){if(queued.contains(id))throw new IllegalStateException("La tarea está en ejecución.");try(StoreLease lease=requireStoreLease()){tasks.put(id,new Task(t.id(),t.name(),t.profile(),t.database(),t.kind(),t.script(),t.hash(),t.output(),t.minutes(),t.next().isBefore(Instant.now())?Instant.now():t.next(),enabled,t.retention()));save();}}}
    public synchronized void delete(String id)throws IOException{if(queued.contains(id)||running.containsKey(id))throw new IllegalStateException("Cancela o espera a que termine la tarea.");try(StoreLease lease=requireStoreLease()){tasks.remove(id);states.remove(id);save();}}
    public synchronized boolean runNow(String id){Task task=tasks.get(id);if(closed||task==null||!queued.add(id))return false;states.put(id,new State(true,"En espera…",state(id).output(),state(id).lastRun()));executor.execute(()->execute(task));return true;}
    public void tick(){runDue();}
    public int runDue(){if(file!=null&&!reload())return 0;int count=0;for(Task task:list())if(task.enabled()&&!task.next().isAfter(Instant.now())&&runNow(task.id()))count++;return count;}
    public boolean awaitIdle(Duration timeout)throws InterruptedException{long end=System.nanoTime()+timeout.toNanos();while(System.nanoTime()<end){synchronized(this){if(queued.isEmpty()&&running.isEmpty())return true;}Thread.sleep(100);}return false;}
    public synchronized boolean reload(){if(!queued.isEmpty()||!running.isEmpty()||file==null)return false;try(StoreLease lease=tryStoreLease()){if(lease==null)return false;tasks.clear();states.clear();signatures.clear();load();return true;}catch(IOException error){return false;}}
    public void cancel(String id){SqlTransferControl control;synchronized(this){control=running.get(id);}if(control!=null){Thread thread=new Thread(control::cancel,"nexodb-job-cancel");thread.setDaemon(true);thread.start();}}
    private char[] password(String profile)throws Exception{synchronized(this){if(passwords.containsKey(profile))return passwords.get(profile).clone();}char[] value=stored.load(profile);if(value==null)throw new IllegalStateException("Falta la contraseña. Abre Tareas programadas y edita la tarea para introducirla.");return value;}
    private void execute(Task task){
        StoreLease lease;try{lease=tryStoreLease();}catch(IOException error){synchronized(this){queued.remove(task.id());states.put(task.id(),new State(false,error.getMessage(),"",Instant.now()));}return;}if(lease==null){synchronized(this){queued.remove(task.id());states.put(task.id(),new State(false,"Otra instancia de AdisDB está ejecutando las tareas.","",Instant.now()));}return;}try{
        SqlTransferControl control=new SqlTransferControl();synchronized(this){if(closed){queued.remove(task.id());return;}running.put(task.id(),control);states.put(task.id(),new State(true,"Ejecutando…","",Instant.now()));}
        String status="",output="";boolean success=false;
        try{
            if(!licenseCheck.getAsBoolean())throw new IllegalStateException("Activa una licencia vigente antes de ejecutar tareas.");
            ConnectionProfile original=profiles.apply(task.profile());if(original==null)throw new IllegalStateException("La conexión de la tarea ya no existe.");
            synchronized(this){if(!signature(original).equals(signatures.get(task.id())))throw new IllegalStateException("La conexión cambió. Edita y revisa de nuevo la tarea antes de ejecutarla.");}
            if(task.kind()==Kind.SQL&&original.readOnly())throw new IllegalStateException("La conexión es de solo lectura. No se ejecutó el script.");
            if(task.kind()!=Kind.BACKUP&&!SqlFiles.diskHash(Path.of(task.script())).equals(task.hash()))throw new IllegalStateException("El archivo SQL cambió. Edita la tarea y vuelve a revisar el archivo antes de ejecutarlo.");
            Path folder=Path.of(task.output()).toAbsolutePath();Files.createDirectories(folder);String artifactPrefix=BackupCatalog.artifactPrefix(task.id(),task.name()),prefix=artifactPrefix+"-"+java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())+"-"+UUID.randomUUID().toString().substring(0,8);
            ConnectionProfile isolated=new ConnectionProfile(original.id(),original.name(),original.type(),original.host(),original.port(),original.database(),original.username(),original.jdbcUrl(),original.driverClass(),original.driverJar(),original.authenticationMode(),original.ssh(),task.kind()!=Kind.SQL);
            char[] secret=password(task.profile());try(JdbcSession session=open(isolated,secret)){
                session.connection().setCatalog(task.database());
                if(original.type()==DatabaseType.POSTGRESQL&&!task.database().equals(session.connection().getCatalog()))throw new IllegalStateException("La base PostgreSQL debe coincidir con la base del perfil de conexión.");
                switch(task.kind()){
                    case BACKUP->{Path path=folder.resolve(prefix+".sql");var result=SqlBackupService.write(session.connection(),isolated.type(),task.database(),SqlBackupService.objects(session.connection(),task.database()),backupOptions(),path,control,p->{});BackupCatalog.record(path,task.id(),task.name(),isolated.type(),task.database(),result.rows());List<Path> removed=BackupCatalog.prune(folder,artifactPrefix,task.retention(),path);output=path.toString();status="Respaldo completo y verificado: "+result.rows()+" filas · incluye DROP IF EXISTS para restauración"+(removed.isEmpty()?"":" · "+removed.size()+" anterior(es) retirado(s)");}
                    case SQL->{var analyzed=SqlImportService.analyze(Path.of(task.script()),control);if(!analyzed.sha256().equals(task.hash()))throw new IOException("El archivo SQL cambió. Revisa de nuevo la tarea.");var result=SqlImportService.restore(session.connection(),analyzed,new SqlImportService.Options(task.database(),false,false,false),control,p->{});status="Script completado: "+result.executed()+" sentencias";}
                    case REPORT->{long rows=report(session.connection(),Path.of(task.script()),task.hash(),folder,prefix,control);output=folder.toString();status="Reporte completo: "+rows+" filas";}
                }
            }
            success=true;
        }catch(Exception error){Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();if(closed)status="Interrumpida al cerrar AdisDB. Queda pendiente para que el ejecutor externo la reintente.";else status=cause instanceof CancellationException?"Cancelada por el usuario. Los cambios DDL ya aplicados pueden permanecer.":Objects.toString(cause.getMessage(),cause.toString());}
        finally{synchronized(this){running.remove(task.id());queued.remove(task.id());states.put(task.id(),new State(false,status,output,Instant.now()));Task current=tasks.get(task.id());if(current!=null)tasks.put(task.id(),afterRun(task,success,closed,Instant.now()));try{save();}catch(IOException error){notify.accept("No se pudo guardar el estado de tareas: "+error.getMessage());}}notify.accept(task.name()+": "+status);}
        }finally{lease.close();}
    }
    private static JdbcSession open(ConnectionProfile profile,char[] password)throws Exception{try{return JdbcSession.open(profile,password);}finally{Arrays.fill(password,'\0');}}
    static SqlBackupService.Options backupOptions(){return new SqlBackupService.Options(SqlBackupService.Mode.STRUCTURE_AND_DATA,false,true,true,true,true);}
    static Task afterRun(Task task,boolean success,boolean closing,Instant now){if(closing&&!success)return new Task(task.id(),task.name(),task.profile(),task.database(),task.kind(),task.script(),task.hash(),task.output(),task.minutes(),now.plusSeconds(60),task.enabled(),task.retention());Instant next=task.minutes()>0?nextScheduled(task,now):task.next();return new Task(task.id(),task.name(),task.profile(),task.database(),task.kind(),task.script(),task.hash(),task.output(),task.minutes(),next,success&&task.enabled()&&task.minutes()>0,task.retention());}
    static Instant nextScheduled(Task task,Instant now){if(task.minutes()<=0)return task.next();long step=task.minutes()*60L;if(task.next().isAfter(now))return task.next();long elapsed=Math.max(0,Duration.between(task.next(),now).getSeconds());return task.next().plusSeconds((elapsed/step+1)*step);}
    private static String signature(ConnectionProfile p){var s=p.ssh();return SqlFiles.hash(String.join("\n",p.id(),p.type().name(),p.host(),""+p.port(),p.database(),p.username(),p.jdbcUrl(),p.driverClass(),p.driverJar(),p.authenticationMode().name(),""+p.readOnly(),""+s.enabled(),s.host(),""+s.port(),s.username(),s.authentication().name(),s.privateKey(),s.knownHosts(),""+s.strictHostKeyChecking(),""+s.localPort(),""+s.connectTimeoutSeconds(),""+s.keepAliveSeconds()));}
    private StoreLease requireStoreLease()throws IOException{StoreLease lease=tryStoreLease();if(lease==null)throw new IOException("Otra instancia está ejecutando o actualizando las tareas. Intenta de nuevo cuando termine.");return lease;}
    private StoreLease tryStoreLease()throws IOException{if(file==null)return StoreLease.noop();Path lock=file.resolveSibling(file.getFileName()+".runner.lock").toAbsolutePath();Files.createDirectories(lock.getParent());FileChannel channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);try{FileLock value;try{value=channel.tryLock();}catch(OverlappingFileLockException error){value=null;}if(value==null){channel.close();return null;}return new StoreLease(channel,value);}catch(IOException|RuntimeException error){try{channel.close();}catch(IOException ignored){}throw error;}}
    private static final class StoreLease implements AutoCloseable {private final FileChannel channel;private final FileLock lock;private StoreLease(FileChannel channel,FileLock lock){this.channel=channel;this.lock=lock;}static StoreLease noop(){return new StoreLease(null,null);}public void close(){try{if(lock!=null&&lock.isValid())lock.release();}catch(IOException ignored){}try{if(channel!=null)channel.close();}catch(IOException ignored){}}}
    private static long report(Connection connection,Path file,String expected,Path output,String prefix,SqlTransferControl control)throws Exception{
        SqlFiles.Loaded loaded=SqlFiles.read(file);if(!loaded.diskHash().equals(expected))throw new IOException("El archivo SQL cambió. Revisa de nuevo la tarea.");
        long rows=0;int index=0;try(SqlScriptReader reader=new SqlScriptReader(new StringReader(loaded.text()))){SqlScriptReader.Statement sql;while((sql=reader.next())!=null){control.check();var tokens=SqlScriptTools.tokens(sql.sql());if(tokens.isEmpty()||!tokens.get(0).is("SELECT"))throw new IllegalArgumentException("Los reportes admiten sentencias SELECT. Para mantenimiento usa una tarea de script SQL.");
            try(Statement statement=connection.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){control.active(statement);statement.setFetchSize(connection.getMetaData().getDriverName().toLowerCase(Locale.ROOT).contains("mysql")?Integer.MIN_VALUE:128);
                try(ResultSet result=statement.executeQuery(sql.sql())){index++;Path html=output.resolve(prefix+"-"+index+".html"),csv=output.resolve(prefix+"-"+index+".csv");rows+=writeReport(result,html,csv,control);}
            }finally{control.active(null);}
        }}if(index==0)throw new IllegalArgumentException("El archivo no contiene consultas SELECT.");return rows;
    }
    private static long writeReport(ResultSet result,Path html,Path csv,SqlTransferControl control)throws Exception{
        Path h=Files.createTempFile(html.getParent(),".report-",".html"),c=Files.createTempFile(csv.getParent(),".report-",".csv");long rows=0;
        try{
            try(Writer web=Files.newBufferedWriter(h);Writer text=Files.newBufferedWriter(c)){web.write("<!doctype html><meta charset=\"utf-8\"><title>AdisDB SQL report</title><style>body{font:14px system-ui}table{border-collapse:collapse}th,td{padding:6px;border:1px solid #ccc}</style><table><thead><tr>");var metadata=result.getMetaData();int count=metadata.getColumnCount();
                for(int i=1;i<=count;i++){String label=metadata.getColumnLabel(i);web.write("<th>"+html(label)+"</th>");if(i>1)text.write(',');text.write(csv(label));}web.write("</tr></thead><tbody>");text.write('\n');
                while(result.next()){control.check();web.write("<tr>");for(int i=1;i<=count;i++){String value=result.getString(i);web.write("<td>"+html(value==null?"(NULL)":value)+"</td>");if(i>1)text.write(',');text.write(csv(value==null?"":value));}web.write("</tr>\n");text.write('\n');rows++;}web.write("</tbody></table>");
            }control.check();Files.move(h,html);Files.move(c,csv);return rows;
        }finally{Files.deleteIfExists(h);Files.deleteIfExists(c);}
    }
    private static String html(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static String csv(String value){return "\""+value.replace("\"","\"\"")+"\"";}
    public void close(){List<SqlTransferControl> controls;synchronized(this){if(closed)return;closed=true;controls=List.copyOf(running.values());}for(SqlTransferControl control:controls){Thread thread=new Thread(control::cancel,"nexodb-job-close");thread.setDaemon(true);thread.start();}executor.shutdown();try{if(!executor.awaitTermination(3,TimeUnit.SECONDS))executor.shutdownNow();}catch(InterruptedException error){Thread.currentThread().interrupt();executor.shutdownNow();}synchronized(this){for(char[] value:passwords.values())Arrays.fill(value,'\0');passwords.clear();}}
}
