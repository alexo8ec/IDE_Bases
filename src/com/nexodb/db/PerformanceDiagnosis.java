package com.nexodb.db;

import java.util.*;

/** Lightweight, explainable diagnosis based only on timings and row counts. */
public final class PerformanceDiagnosis {
    public enum Severity { INFO, WARNING, CRITICAL }
    public record Finding(Severity severity,String title,String detail) {}
    private PerformanceDiagnosis(){}
    public static List<Finding> result(QueryResult.Timing timing,long rows,long renderMillis){
        List<Finding> out=new ArrayList<>();long total=Math.max(1,timing.totalMillis());
        if(timing.serverMillis()>2_000&&timing.serverMillis()*100/total>=65)out.add(new Finding(Severity.WARNING,"Servidor o red lentos","La mayor parte del tiempo transcurrió antes de recibir filas. Revisa bloqueos, plan, índices y latencia de red."));
        if(timing.fetchMillis()>2_000||rows>20_000)out.add(new Finding(Severity.WARNING,"Volumen de transferencia alto","Reduce columnas o filas, usa filtros y conserva la paginación por clave."));
        if(timing.metadataMillis()>1_000)out.add(new Finding(Severity.INFO,"Metadatos lentos","La lectura de claves y metadatos demoró; puede deberse a catálogo grande o permisos limitados."));
        if(renderMillis>1_000&&renderMillis>timing.serverMillis())out.add(new Finding(Severity.WARNING,"Representación de la cuadrícula lenta","La interfaz tarda más que el servidor. Reduce el tamaño de página o columnas visibles."));
        if(total>10_000)out.add(new Finding(Severity.CRITICAL,"Consulta muy lenta","Superó 10 segundos. Abre el plan de ejecución y revisa el monitor de bloqueos."));
        return List.copyOf(out);
    }
    public static String summary(List<Finding> findings){if(findings.isEmpty())return "";Finding first=findings.stream().max(Comparator.comparing(Finding::severity)).orElse(findings.get(0));return first.title()+": "+first.detail();}
}
