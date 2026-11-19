package com.nexodb.tools;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

public final class ExternalTaskSchedulerTest {
    public static void main(String[] args)throws Exception{
        Path folder=Files.createTempDirectory("AdisDB Tasks "),executable=Files.writeString(folder.resolve("AdisDB Tasks.exe"),"test");List<List<String>> commands=new ArrayList<>();boolean[] installed={false};ExternalTaskScheduler.Runner runner=(command,timeout)->{commands.add(List.copyOf(command));require(timeout.compareTo(Duration.ofSeconds(30))<0,"Unbounded command timeout");if(command.contains("/Query"))return new ExternalTaskScheduler.Result(installed[0]?0:1,"");if(command.contains("/Create")){installed[0]=true;return new ExternalTaskScheduler.Result(0,"created");}if(command.contains("/Delete")){installed[0]=false;return new ExternalTaskScheduler.Result(0,"deleted");}return new ExternalTaskScheduler.Result(0,"started");};ExternalTaskScheduler scheduler=new ExternalTaskScheduler(ExternalTaskScheduler.Platform.WINDOWS,executable,runner);
        require(!scheduler.status().installed(),"Unexpected installed task");scheduler.install();require(scheduler.status().installed(),"Task was not installed");scheduler.runNow();scheduler.uninstall();require(!scheduler.status().installed(),"Task was not removed");String all=commands.toString();require(all.contains("/SC, MINUTE")&&all.contains("/MO, 1")&&all.contains("--run-due"),all);require(!all.toLowerCase(Locale.ROOT).contains("password"),"Credential leaked to task command");
        var unsupported=new ExternalTaskScheduler(ExternalTaskScheduler.Platform.OTHER,executable,runner).status();require(!unsupported.supported(),"Unsupported platform accepted");System.out.println("ExternalTaskSchedulerTest: OK (safe Windows registration, one-minute schedule checks, run/remove and no credentials)");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
