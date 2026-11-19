package com.nexodb.tools;

import com.nexodb.util.AtomicProperties;
import com.nexodb.util.SqlFiles;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Safe local SQL project and Git adapter. It never executes SQL or stores Git credentials. */
public final class SqlProjectService {
    public static final String DESCRIPTOR=".adisdb-project.properties";
    public record Entry(Path relative,boolean directory,String gitStatus){}
    public record GitResult(int exitCode,String output){public boolean ok(){return exitCode==0;}}
    private final Path root;

    private SqlProjectService(Path root){this.root=root.toAbsolutePath().normalize();}
    public static SqlProjectService create(Path root,String name)throws IOException{
        Path normalized=root.toAbsolutePath().normalize();Files.createDirectories(normalized);Properties values=new Properties();values.setProperty("version","1");values.setProperty("name",name==null||name.isBlank()?normalized.getFileName().toString():name.trim());AtomicProperties.write(normalized.resolve(DESCRIPTOR),values);return new SqlProjectService(normalized);
    }
    public static SqlProjectService open(Path root)throws IOException{Path normalized=root.toAbsolutePath().normalize();if(!Files.isDirectory(normalized))throw new IOException("La carpeta del proyecto no existe.");return new SqlProjectService(normalized);}
    public Path root(){return root;}
    public String name(){try{Properties values=AtomicProperties.read(root.resolve(DESCRIPTOR));return values.getProperty("name",root.getFileName().toString());}catch(IOException error){return root.getFileName().toString();}}

    public List<Entry> entries()throws IOException{
        Map<String,String> states=gitStatuses();List<Entry> entries=new ArrayList<>();
        try(Stream<Path> stream=Files.walk(root,20)){stream.filter(path->!path.equals(root)).filter(path->!insideGit(path)).filter(path->Files.isDirectory(path)||Files.isRegularFile(path)&&isSql(path)).limit(20_000).forEach(path->{Path relative=root.relativize(path);entries.add(new Entry(relative,Files.isDirectory(path),states.getOrDefault(portable(relative),"")));});}
        entries.sort(Comparator.comparing(Entry::directory).reversed().thenComparing(entry->portable(entry.relative()),String.CASE_INSENSITIVE_ORDER));return List.copyOf(entries);
    }
    public SqlFiles.Loaded read(Path relative)throws IOException{return SqlFiles.read(resolveSql(relative,false));}
    public String write(Path relative,String text)throws IOException{Path target=resolveSql(relative,true);Files.createDirectories(target.getParent());return SqlFiles.write(target,text);}
    public Path createFolder(Path relative)throws IOException{Path target=resolve(relative,true);Files.createDirectories(target);return root.relativize(target);}
    public Path createSql(Path relative)throws IOException{Path target=SqlFiles.withExtension(resolve(relative,true));if(Files.exists(target))throw new FileAlreadyExistsException(target.toString());Files.createDirectories(target.getParent());SqlFiles.write(target,"-- "+target.getFileName()+"\n");return root.relativize(target);}
    public Path rename(Path relative,String newName)throws IOException{
        Path source=resolve(relative,false);if(newName==null||newName.isBlank()||newName.contains("/")||newName.contains("\\"))throw new IOException("Escribe un nombre sin rutas.");Path replacement=Files.isDirectory(source)?Path.of(newName.trim()):SqlFiles.withExtension(Path.of(newName.trim())).getFileName();Path target=source.resolveSibling(replacement).normalize();requireInside(target);if(Files.exists(target))throw new FileAlreadyExistsException(target.toString());Files.move(source,target);return root.relativize(target);
    }
    public void delete(Path relative)throws IOException{Path target=resolve(relative,false);if(Files.isDirectory(target)){try(Stream<Path> children=Files.list(target)){if(children.findAny().isPresent())throw new IOException("La carpeta no está vacía.");}}Files.delete(target);}
    public boolean gitAvailable(){try{return runGit(List.of("--version"),root,Duration.ofSeconds(5)).ok();}catch(Exception error){return false;}}
    public boolean isGitRepository(){return Files.exists(root.resolve(".git"));}
    public GitResult initGit()throws IOException{return runGit(List.of("init"),root,Duration.ofSeconds(20));}
    public String branch(){if(!isGitRepository())return "";try{GitResult result=git(List.of("branch","--show-current"));return result.ok()?result.output().trim():"";}catch(IOException error){return "";}}
    public Map<String,String> gitStatuses(){
        if(!isGitRepository())return Map.of();try{GitResult result=git(List.of("status","--porcelain=v1","--untracked-files=all"));if(!result.ok())return Map.of();Map<String,String> states=new LinkedHashMap<>();for(String line:result.output().split("\\R")){if(line.length()<4)continue;String state=line.substring(0,2).trim();String path=line.substring(3).trim();int arrow=path.indexOf(" -> ");if(arrow>=0)path=path.substring(arrow+4);states.put(path.replace('\\','/'),state.isBlank()?"?":state);}return Map.copyOf(states);}catch(IOException error){return Map.of();}
    }
    public GitResult stage(Path relative)throws IOException{return git(List.of("add","--",portable(root.relativize(resolve(relative,false)))));}
    public GitResult unstage(Path relative)throws IOException{return git(List.of("restore","--staged","--",portable(root.relativize(resolve(relative,false)))));}
    public GitResult stageAll()throws IOException{return git(List.of("add","--all","--","."));}
    public GitResult commit(String message)throws IOException{if(message==null||message.isBlank())throw new IOException("Escribe un mensaje para el commit.");return git(List.of("commit","-m",message.trim()));}
    public GitResult configureIdentity(String name,String email)throws IOException{if(name==null||name.isBlank()||email==null||email.isBlank())throw new IOException("Nombre y correo Git son obligatorios.");GitResult first=git(List.of("config","user.name",name.trim()));return first.ok()?git(List.of("config","user.email",email.trim())):first;}
    public String diff(Path relative)throws IOException{
        Path target=resolveSql(relative,false);String path=portable(root.relativize(target));GitResult work=git(List.of("diff","--no-ext-diff","--color=never","--",path)),staged=git(List.of("diff","--cached","--no-ext-diff","--color=never","--",path));String text=(staged.output()+System.lineSeparator()+work.output()).trim();if(text.isBlank()&&gitStatuses().containsKey(path))text="Archivo sin seguimiento: "+path+"\n\n"+Files.readString(target);return text;
    }
    public String history(Path relative)throws IOException{List<String> command=new ArrayList<>(List.of("log","--max-count=100","--date=iso-local","--pretty=format:%h  %ad  %an%n    %s"));if(relative!=null){command.add("--");command.add(portable(root.relativize(resolve(relative,false))));}GitResult result=git(command);return result.output();}
    public GitResult gitSummary()throws IOException{return git(List.of("status","--short","--branch"));}

    private GitResult git(List<String> arguments)throws IOException{if(!isGitRepository())throw new IOException("El proyecto todavía no es un repositorio Git.");return runGit(arguments,root,Duration.ofSeconds(20));}
    private Path resolveSql(Path relative,boolean mayCreate)throws IOException{Path path=resolve(relative,mayCreate);if(!isSql(path))throw new IOException("El proyecto solo edita archivos .sql.");return path;}
    private Path resolve(Path relative,boolean mayCreate)throws IOException{if(relative==null||relative.isAbsolute())throw new IOException("Ruta de proyecto no válida.");Path target=root.resolve(relative).normalize();requireInside(target);if(insideGit(target))throw new IOException("La carpeta interna de Git está protegida.");if(!mayCreate&&!Files.exists(target))throw new NoSuchFileException(target.toString());return target;}
    private void requireInside(Path target)throws IOException{if(!target.startsWith(root)||target.equals(root))throw new IOException("La ruta debe permanecer dentro del proyecto.");}
    private boolean insideGit(Path path){return path.normalize().startsWith(root.resolve(".git"));}
    private static boolean isSql(Path path){return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql");}
    private static String portable(Path path){return path.toString().replace('\\','/');}
    private static GitResult runGit(List<String> arguments,Path directory,Duration timeout)throws IOException{
        List<String> command=new ArrayList<>();command.add("git");command.addAll(arguments);ProcessBuilder builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);builder.environment().put("GIT_TERMINAL_PROMPT","0");Process process=builder.start();ByteArrayOutputStream output=new ByteArrayOutputStream();Thread reader=new Thread(()->{try(InputStream input=process.getInputStream()){byte[] block=new byte[8192];int read,total=0;while((read=input.read(block))!=-1){int keep=Math.min(read,2_000_000-total);if(keep>0){output.write(block,0,keep);total+=keep;}}}catch(IOException ignored){}},"adisdb-git-output");reader.setDaemon(true);reader.start();
        try{if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS)){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();throw new IOException("Git superó el tiempo límite de "+timeout.toSeconds()+" segundos.");}reader.join(2_000);}catch(InterruptedException error){Thread.currentThread().interrupt();process.destroyForcibly();throw new IOException("Operación Git interrumpida.",error);}String text=output.toString(StandardCharsets.UTF_8);return new GitResult(process.exitValue(),text.stripTrailing());
    }
}
