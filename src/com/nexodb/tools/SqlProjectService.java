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
    public record GitCommit(String hash,String shortHash,List<String> parents,String refs,String author,String date,String subject){}
    public record GitGraphLine(String graph,GitCommit commit){}
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
    public String remoteUrl(){if(!isGitRepository())return "";try{GitResult result=git(List.of("remote","get-url","origin"));return result.ok()?result.output().trim():"";}catch(IOException error){return "";}}
    public GitResult configureRemote(String url,String branch)throws IOException{String remote=safeRemote(url),target=safeBranch(branch);GitResult configured=remoteUrl().isBlank()?git(List.of("remote","add","origin",remote)):git(List.of("remote","set-url","origin",remote));if(!configured.ok())return configured;return git(List.of("branch","-M",target));}
    public GitResult pull()throws IOException{return gitNetwork(List.of("pull","--ff-only","origin",requiredBranch()));}
    public GitResult push()throws IOException{return gitNetwork(List.of("push","--set-upstream","origin",requiredBranch()));}
    public GitResult sync()throws IOException{String current=requiredBranch();GitResult exists=gitNetwork(List.of("ls-remote","--exit-code","--heads","origin","refs/heads/"+current));if(!exists.ok())return push();GitResult pulled=gitNetwork(List.of("pull","--ff-only","origin",current));return pulled.ok()?push():pulled;}
    public boolean githubLoginAvailable(){try{return runGit(List.of("credential-manager","github","--help"),root,Duration.ofSeconds(8)).ok()||runCommand(List.of("gh","--version"),root,Duration.ofSeconds(8)).ok();}catch(Exception error){return false;}}
    public GitResult githubLogin()throws IOException{GitResult manager=runGit(List.of("credential-manager","github","--help"),root,Duration.ofSeconds(8));if(manager.ok())return runGit(List.of("credential-manager","github","login"),root,Duration.ofMinutes(10));GitResult cli=runCommand(List.of("gh","--version"),root,Duration.ofSeconds(8));if(cli.ok())return runCommand(List.of("gh","auth","login","--web","--git-protocol","https","--hostname","github.com"),root,Duration.ofMinutes(10));return new GitResult(127,"Instala Git Credential Manager o GitHub CLI para autenticarte mediante el navegador.");}
    public String githubAccounts(){try{GitResult manager=runGit(List.of("credential-manager","github","list"),root,Duration.ofSeconds(15));if(manager.ok())return manager.output().isBlank()?"Sin cuentas de GitHub registradas.":manager.output();GitResult cli=runCommand(List.of("gh","auth","status","--hostname","github.com"),root,Duration.ofSeconds(15));return cli.output().isBlank()?"No se encontró una cuenta GitHub configurada.":cli.output();}catch(Exception error){return "No se pudo consultar la cuenta GitHub: "+error.getMessage();}}
    public String diff(Path relative)throws IOException{
        Path target=resolveSql(relative,false);String path=portable(root.relativize(target));GitResult work=git(List.of("diff","--no-ext-diff","--color=never","--",path)),staged=git(List.of("diff","--cached","--no-ext-diff","--color=never","--",path));String text=(staged.output()+System.lineSeparator()+work.output()).trim();if(text.isBlank()&&gitStatuses().containsKey(path))text="Archivo sin seguimiento: "+path+"\n\n"+Files.readString(target);return text;
    }
    public String history(Path relative)throws IOException{List<String> command=new ArrayList<>(List.of("log","--max-count=100","--date=iso-local","--pretty=format:%h  %ad  %an%n    %s"));if(relative!=null){command.add("--");command.add(portable(root.relativize(resolve(relative,false))));}GitResult result=git(command);return result.output();}
    public List<GitGraphLine> historyGraph(Path relative)throws IOException{
        List<String> command=new ArrayList<>(List.of("log","--graph","--all","--decorate=short","--max-count=250","--date=short","--pretty=format:%x1e%H%x1f%h%x1f%P%x1f%D%x1f%an%x1f%ad%x1f%s"));
        if(relative!=null){command.add("--");command.add(portable(root.relativize(resolve(relative,false))));}
        GitResult result=git(command);if(!result.ok())throw new IOException(result.output());List<GitGraphLine> lines=new ArrayList<>();
        for(String line:result.output().split("\\R",-1)){int marker=line.indexOf('\u001e');if(marker<0){if(!line.isBlank())lines.add(new GitGraphLine(line,null));continue;}String[] fields=line.substring(marker+1).split("\\x1f",7);if(fields.length<7)continue;List<String> parents=fields[2].isBlank()?List.of():List.of(fields[2].trim().split("\\s+"));GitCommit commit=new GitCommit(fields[0],fields[1],parents,fields[3],fields[4],fields[5],fields[6]);lines.add(new GitGraphLine(line.substring(0,marker),commit));}
        return List.copyOf(lines);
    }
    public String commitDetails(String hash)throws IOException{if(hash==null||!hash.matches("[0-9a-fA-F]{7,64}"))throw new IOException("Identificador de commit no válido.");GitResult result=git(List.of("show","--no-renames","--stat","--format=commit %H%nAutor: %an <%ae>%nFecha: %ad%n%n    %s%n%n%b","--date=iso-local","--max-count=1",hash));if(!result.ok())throw new IOException(result.output());return result.output();}
    public GitResult runConsole(String command)throws IOException{
        List<String> parts=parseCommand(command);if(!parts.isEmpty()&&parts.get(0).equalsIgnoreCase("git"))parts=new ArrayList<>(parts.subList(1,parts.size()));if(parts.isEmpty())throw new IOException("Escribe un comando Git.");
        Set<String> allowed=Set.of("status","diff","log","show","branch","switch","checkout","merge","rebase","add","restore","commit","fetch","pull","push","tag","remote","stash");String action=parts.get(0).toLowerCase(Locale.ROOT);if(!allowed.contains(action))throw new IOException("Comando no permitido en esta consola: "+action+". Usa solo operaciones Git del proyecto.");
        if(parts.stream().anyMatch(value->value.indexOf('\0')>=0||value.contains("\n")||value.contains("\r")))throw new IOException("El comando contiene caracteres no válidos.");return runGit(parts,root,Duration.ofMinutes(3));
    }
    public GitResult gitSummary()throws IOException{return git(List.of("status","--short","--branch"));}

    private GitResult git(List<String> arguments)throws IOException{if(!isGitRepository())throw new IOException("El proyecto todavía no es un repositorio Git.");return runGit(arguments,root,Duration.ofSeconds(20));}
    private GitResult gitNetwork(List<String> arguments)throws IOException{if(remoteUrl().isBlank())throw new IOException("Configura primero el repositorio remoto de GitHub.");return runGit(arguments,root,Duration.ofMinutes(5));}
    private String requiredBranch()throws IOException{String value=branch();if(value.isBlank())throw new IOException("Crea al menos un commit antes de enviar el proyecto.");return safeBranch(value);}
    private static String safeRemote(String value)throws IOException{String remote=Objects.toString(value,"").trim();if(remote.isBlank()||remote.length()>2048||remote.indexOf('\n')>=0||remote.indexOf('\r')>=0)throw new IOException("Escribe una URL válida del repositorio GitHub.");return remote;}
    private static String safeBranch(String value)throws IOException{String branch=Objects.toString(value,"main").trim();if(branch.isBlank()||branch.startsWith("-")||branch.contains("..")||!branch.matches("[A-Za-z0-9._/-]+"))throw new IOException("El nombre de la rama no es válido.");return branch;}
    private Path resolveSql(Path relative,boolean mayCreate)throws IOException{Path path=resolve(relative,mayCreate);if(!isSql(path))throw new IOException("El proyecto solo edita archivos .sql.");return path;}
    private Path resolve(Path relative,boolean mayCreate)throws IOException{if(relative==null||relative.isAbsolute())throw new IOException("Ruta de proyecto no válida.");Path target=root.resolve(relative).normalize();requireInside(target);if(insideGit(target))throw new IOException("La carpeta interna de Git está protegida.");if(!mayCreate&&!Files.exists(target))throw new NoSuchFileException(target.toString());return target;}
    private void requireInside(Path target)throws IOException{if(!target.startsWith(root)||target.equals(root))throw new IOException("La ruta debe permanecer dentro del proyecto.");}
    private boolean insideGit(Path path){return path.normalize().startsWith(root.resolve(".git"));}
    private static boolean isSql(Path path){return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql");}
    private static String portable(Path path){return path.toString().replace('\\','/');}
    private static List<String> parseCommand(String input)throws IOException{String text=Objects.toString(input,"").trim();List<String> result=new ArrayList<>();StringBuilder token=new StringBuilder();char quote=0;boolean escaped=false;for(int i=0;i<text.length();i++){char ch=text.charAt(i);if(escaped){token.append(ch);escaped=false;continue;}if(ch=='\\'&&quote!=0){escaped=true;continue;}if((ch=='\"'||ch=='\'')&&(quote==0||quote==ch)){quote=quote==0?ch:0;continue;}if(Character.isWhitespace(ch)&&quote==0){if(token.length()>0){result.add(token.toString());token.setLength(0);}continue;}token.append(ch);}if(escaped)token.append('\\');if(quote!=0)throw new IOException("Falta cerrar una comilla en el comando Git.");if(token.length()>0)result.add(token.toString());return result;}
    private static GitResult runGit(List<String> arguments,Path directory,Duration timeout)throws IOException{
        List<String> command=new ArrayList<>();command.add("git");command.addAll(arguments);return runCommand(command,directory,timeout);
    }
    private static GitResult runCommand(List<String> command,Path directory,Duration timeout)throws IOException{
        ProcessBuilder builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);builder.environment().put("GIT_TERMINAL_PROMPT","0");Process process;try{process=builder.start();}catch(IOException error){return new GitResult(127,"No se encontró "+command.get(0)+" en el equipo.");}ByteArrayOutputStream output=new ByteArrayOutputStream();Thread reader=new Thread(()->{try(InputStream input=process.getInputStream()){byte[] block=new byte[8192];int read,total=0;while((read=input.read(block))!=-1){int keep=Math.min(read,2_000_000-total);if(keep>0){output.write(block,0,keep);total+=keep;}}}catch(IOException ignored){}},"adisdb-git-output");reader.setDaemon(true);reader.start();
        try{if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS)){process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS))process.destroyForcibly();throw new IOException("Git superó el tiempo límite de "+timeout.toSeconds()+" segundos.");}reader.join(2_000);}catch(InterruptedException error){Thread.currentThread().interrupt();process.destroyForcibly();throw new IOException("Operación Git interrumpida.",error);}String text=output.toString(StandardCharsets.UTF_8);return new GitResult(process.exitValue(),text.stripTrailing());
    }
}
