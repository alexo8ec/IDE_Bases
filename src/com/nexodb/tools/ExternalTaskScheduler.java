package com.nexodb.tools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Registers the packaged one-shot task runner without placing credentials on a command line. */
public final class ExternalTaskScheduler {
    public static final String TASK_NAME="AdisDB-Background-Tasks";
    public enum Platform { WINDOWS,OTHER }
    public record Result(int exitCode,String output){public boolean ok(){return exitCode==0;}}
    public record Status(boolean supported,boolean installed,String detail,Path executable){}
    @FunctionalInterface public interface Runner {Result run(List<String> command,Duration timeout)throws IOException;}
    private final Platform platform;private final Path executable;private final Runner runner;

    public ExternalTaskScheduler(Platform platform,Path executable,Runner runner){this.platform=platform;this.executable=executable==null?null:executable.toAbsolutePath().normalize();this.runner=runner;}
    public static ExternalTaskScheduler configured(){String os=System.getProperty("os.name","").toLowerCase(Locale.ROOT);Platform platform=os.startsWith("windows")?Platform.WINDOWS:Platform.OTHER;return new ExternalTaskScheduler(platform,discoverExecutable(platform),ExternalTaskScheduler::execute);}
    public Status status(){if(platform!=Platform.WINDOWS)return new Status(false,false,"El ejecutor externo se incorporará al empaquetado nativo de este sistema.",executable);if(executable==null||!Files.isRegularFile(executable))return new Status(false,false,"El ejecutor está disponible en la aplicación instalada, no en este entorno de desarrollo.",executable);try{Result result=runner.run(List.of("schtasks.exe","/Query","/TN",TASK_NAME,"/FO","LIST"),Duration.ofSeconds(12));return new Status(true,result.ok(),result.ok()?"Ejecutor externo activo cada 5 minutos.":"Ejecutor externo no instalado.",executable);}catch(IOException error){return new Status(true,false,"No se pudo consultar el Programador de tareas: "+error.getMessage(),executable);}}
    public Result install()throws IOException{requireSupported();String invocation=quote(executable.toString())+" --run-due";Result result=runner.run(List.of("schtasks.exe","/Create","/TN",TASK_NAME,"/TR",invocation,"/SC","MINUTE","/MO","5","/RL","LIMITED","/F"),Duration.ofSeconds(20));if(!result.ok())throw new IOException(message(result,"Windows no pudo instalar el ejecutor externo."));return result;}
    public Result uninstall()throws IOException{requireSupported();Result result=runner.run(List.of("schtasks.exe","/Delete","/TN",TASK_NAME,"/F"),Duration.ofSeconds(20));if(!result.ok())throw new IOException(message(result,"Windows no pudo retirar el ejecutor externo."));return result;}
    public Result runNow()throws IOException{requireSupported();Result result=runner.run(List.of("schtasks.exe","/Run","/TN",TASK_NAME),Duration.ofSeconds(20));if(!result.ok())throw new IOException(message(result,"Windows no pudo iniciar la comprobación de tareas."));return result;}
    private void requireSupported()throws IOException{Status status=status();if(!status.supported())throw new IOException(status.detail());}
    private static String quote(String value)throws IOException{if(value.indexOf('"')>=0||value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw new IOException("Ruta del ejecutor no válida.");return '"'+value+'"';}
    private static String message(Result result,String fallback){return result.output().isBlank()?fallback:result.output();}
    private static Path discoverExecutable(Platform platform){String override=System.getProperty("nexodb.taskRunnerPath","").trim();if(!override.isBlank())return Path.of(override);if(platform!=Platform.WINDOWS)return null;String command=ProcessHandle.current().info().command().orElse("");if(command.isBlank())return null;Path sibling=Path.of(command).toAbsolutePath().resolveSibling("AdisDB Tasks.exe");return Files.isRegularFile(sibling)?sibling:null;}
    private static Result execute(List<String> command,Duration timeout)throws IOException{Process process=new ProcessBuilder(command).redirectErrorStream(true).start();ByteArrayOutputStream output=new ByteArrayOutputStream();Thread reader=new Thread(()->{try(InputStream input=process.getInputStream()){byte[] buffer=new byte[8192];int read,total=0;while((read=input.read(buffer))!=-1){int keep=Math.min(read,256_000-total);if(keep>0){output.write(buffer,0,keep);total+=keep;}}}catch(IOException ignored){}});reader.setDaemon(true);reader.start();try{if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS)){process.destroyForcibly();throw new IOException("El Programador de tareas no respondió a tiempo.");}reader.join(2_000);return new Result(process.exitValue(),output.toString(StandardCharsets.UTF_8).strip());}catch(InterruptedException error){Thread.currentThread().interrupt();process.destroyForcibly();throw new IOException("Operación interrumpida.",error);}}
}
