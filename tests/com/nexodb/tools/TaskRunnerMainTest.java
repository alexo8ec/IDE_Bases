package com.nexodb.tools;

import java.nio.file.*;
import java.util.*;

public final class TaskRunnerMainTest {
    public static void main(String[] args)throws Exception{
        String old=System.getProperty("nexodb.settingsFile");Path root=Files.createTempDirectory("adisdb-runner-");try{System.setProperty("nexodb.settingsFile",root.resolve("settings.properties").toString());equal(2,TaskRunnerMain.run(new String[]{"--invalid"}));equal(0,TaskRunnerMain.run(new String[]{"--run-due"}));require(Files.isRegularFile(root.resolve("trial.properties")),"Headless runner did not enforce/create trial state");}finally{if(old==null)System.clearProperty("nexodb.settingsFile");else System.setProperty("nexodb.settingsFile",old);}
        System.out.println("TaskRunnerMainTest: OK (one-shot headless runner, argument validation and shared trial licensing)");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
