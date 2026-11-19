package com.nexodb.db;

public final class PerformanceDiagnosisTest {
    public static void main(String[] args){
        var server=PerformanceDiagnosis.result(new QueryResult.Timing(12_000,10_500,900,100),500,100);if(server.stream().noneMatch(f->f.title().contains("Servidor"))||server.stream().noneMatch(f->f.severity()==PerformanceDiagnosis.Severity.CRITICAL))throw new AssertionError("Server diagnosis missing");var ui=PerformanceDiagnosis.result(new QueryResult.Timing(900,300,500,100),100,2_000);if(ui.stream().noneMatch(f->f.title().contains("cuadrícula")))throw new AssertionError("UI diagnosis missing");
        System.out.println("PerformanceDiagnosisTest: OK (server, transfer, metadata and UI signals)");
    }
}
