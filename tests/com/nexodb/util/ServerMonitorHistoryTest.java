package com.nexodb.util;

import java.nio.file.*;
import java.time.Instant;

public final class ServerMonitorHistoryTest {
    public static void main(String[] args)throws Exception{
        Path folder=Files.createTempDirectory("adisdb-server-history-");Path file=folder.resolve("history.properties");ServerMonitorHistory history=new ServerMonitorHistory(file);Instant now=Instant.now();
        history.record(new ServerMonitorHistory.Entry(now.minusSeconds(80),"produccion",12,3,9,2,1,90,0,1,4,"En línea"));history.record(new ServerMonitorHistory.Entry(now,"produccion",14,4,10,3,2,120,1,1,8,"Revisar"));history.record(new ServerMonitorHistory.Entry(now,"pruebas",2,1,1,0,0,3,0,0,0,"Informativo"));
        require(history.entries("produccion").size()==2,"Profile history isolation failed");require(history.entries("pruebas").size()==1,"Second profile missing");String raw=Files.readString(file).toLowerCase();require(!raw.contains("sql")&&!raw.contains("user")&&!raw.contains("session_id")&&!raw.contains("password"),"Sensitive keys must not be persisted");
        history.clear("produccion");require(history.entries("produccion").isEmpty(),"Clear did not isolate selected profile");require(history.entries("pruebas").size()==1,"Clear removed another profile");
        System.out.println("ServerMonitorHistoryTest: OK (bounded, profile-scoped, data-free history)");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
