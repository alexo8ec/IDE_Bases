package com.nexodb.db;
import java.util.List;
public final class QueryProfilerTest {
 public static void main(String[] args){
  String a=QueryProfiler.fingerprint("select * from clientes where id=42 and nombre='Ana'");String b=QueryProfiler.fingerprint("SELECT * FROM clientes WHERE id=99 AND nombre='Luis'");equal(a,b);if(a.contains("Ana")||a.contains("42"))throw new AssertionError("Sensitive literal retained");
  var profiler=new QueryProfiler();profiler.sample(List.of(new ServerActivity.Session("1","u","d","active",7,"select * from clientes where id=42","","","",false),new ServerActivity.Session("2","u","d","active",3,"select * from clientes where id=99","1","","",false)));var metric=profiler.metrics().get(0);equal(2L,metric.samples());equal(10L,metric.activeSeconds());equal(1L,metric.blockedSamples());System.out.println("QueryProfilerTest: OK (private fingerprints and aggregation)");
 }
 private static void equal(Object e,Object a){if(!java.util.Objects.equals(e,a))throw new AssertionError("Expected "+e+" got "+a);}
}
