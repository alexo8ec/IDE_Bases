package com.nexodb.db;

import com.nexodb.model.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class ScheduledTaskProcessLockTest {
    public static void main(String[] args)throws Exception{
        Path folder=Files.createTempDirectory("adisdb-task-lock-"),store=folder.resolve("scheduled-tasks.properties");ConnectionProfile profile=new ConnectionProfile("h2","H2",DatabaseType.H2,"",0,"PUBLIC","sa","jdbc:h2:mem:lock","org.h2.Driver","",false);ScheduledTasks.Task task=new ScheduledTasks.Task("one","Respaldo",profile.id(),"PUBLIC",ScheduledTasks.Kind.BACKUP,"","",folder.toString(),5,Instant.now().minusSeconds(1),true,3);
        try(ScheduledTasks tasks=new ScheduledTasks(store,id->profile,id->new char[0],message->{},false)){tasks.put(task);Path lock=store.resolveSibling(store.getFileName()+".runner.lock");try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock held=channel.lock()){equal(0,tasks.runDue());equal(true,tasks.runNow(task.id()));require(tasks.awaitIdle(Duration.ofSeconds(5)),"Locked task did not finish");require(tasks.state(task.id()).status().contains("Otra instancia"),tasks.state(task.id()).status());try{tasks.enable(task.id(),false);throw new AssertionError("Store mutation accepted while external runner holds lock");}catch(java.io.IOException expected){}}}
        try(ScheduledTasks reopened=new ScheduledTasks(store,id->profile,id->new char[0],message->{},false)){equal(3,reopened.list().get(0).retention());}
        equal(SqlBackupService.Mode.STRUCTURE_AND_DATA,ScheduledTasks.backupOptions().mode());equal(true,ScheduledTasks.backupOptions().drop());equal(true,ScheduledTasks.backupOptions().disableForeignKeys());
        Instant now=Instant.parse("2026-11-19T20:00:00Z");ScheduledTasks.Task anchored=new ScheduledTasks.Task(task.id(),task.name(),task.profile(),task.database(),task.kind(),task.script(),task.hash(),task.output(),5,now,true,task.retention());ScheduledTasks.Task handoff=ScheduledTasks.afterRun(anchored,false,true,now);equal(true,handoff.enabled());equal(now.plusSeconds(60),handoff.next());ScheduledTasks.Task cancelled=ScheduledTasks.afterRun(anchored,false,false,now);equal(false,cancelled.enabled());ScheduledTasks.Task completed=ScheduledTasks.afterRun(anchored,true,false,now);equal(true,completed.enabled());equal(now.plusSeconds(300),completed.next());ScheduledTasks.Task daily=new ScheduledTasks.Task(task.id(),task.name(),task.profile(),task.database(),task.kind(),task.script(),task.hash(),task.output(),1440,Instant.parse("2026-11-19T15:30:00Z"),true,task.retention());equal(Instant.parse("2026-11-20T15:30:00Z"),ScheduledTasks.nextScheduled(daily,Instant.parse("2026-11-19T16:45:00Z")));
        System.out.println("ScheduledTaskProcessLockTest: OK (cross-process exclusion, close handoff, persisted retention and restorable DROP IF EXISTS backups)");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
