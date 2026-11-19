package com.nexodb.tools;

import com.nexodb.db.ScheduledTasks;
import com.nexodb.licensing.LicenseManager;
import com.nexodb.model.ConnectionProfile;
import com.nexodb.util.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Headless, one-shot entry point used by the operating-system scheduler. */
public final class TaskRunnerMain {
    private TaskRunnerMain(){}
    public static void main(String[] args){int result=run(args);if(result!=0)System.exit(result);}
    public static int run(String[] args){
        if(args.length>0&&!Arrays.asList(args).contains("--run-due")){System.err.println("Uso: AdisDB Tasks --run-due");return 2;}I18n.initialize(AppSettings.get("language","es"));
        BooleanSupplier license=TaskRunnerMain::licensed;try{if(!license.getAsBoolean()){System.err.println("La prueba terminó o la licencia no es válida. Abre AdisDB para activar una licencia.");return 3;}ProfileStore profiles=new ProfileStore();Map<String,ConnectionProfile> byId=new HashMap<>();for(ConnectionProfile profile:profiles.load())byId.put(profile.id(),profile);SecretStore secrets=new SecretStore();Path file=AppSettings.file().resolveSibling("scheduled-tasks.properties");try(ScheduledTasks tasks=new ScheduledTasks(file,byId::get,id->secrets.has(id)?secrets.load(id):null,System.out::println,false)){tasks.setLicenseCheck(license);int due=tasks.runDue();if(!tasks.awaitIdle(Duration.ofHours(12))){System.err.println("Las tareas no terminaron dentro del límite externo.");return 4;}System.out.println("AdisDB: "+due+" tarea(s) revisadas.");return 0;}}catch(Exception error){Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();System.err.println("AdisDB Tasks: "+Objects.toString(cause.getMessage(),cause.toString()));return 1;}}
    static boolean licensed(){try{LicenseManager manager=LicenseManager.configured();Instant now=Instant.now();try{var license=manager.installed(now);manager.record(license,now);return true;}catch(Exception ignored){return manager.trial(now).active();}}catch(Exception error){return false;}}
}
