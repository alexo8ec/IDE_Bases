package com.nexodb.reporting;

import java.math.BigDecimal;
import java.util.*;

/** Detached input contract. The reporting engine has no connection or SQL execution API. */
public record ReportData(String name, List<String> columns, List<List<Object>> rows, boolean limited) {
    public ReportData {
        name=Objects.requireNonNullElse(name,"");
        columns=List.copyOf(columns);
        List<List<Object>> snapshot=new ArrayList<>(rows.size());
        for (List<?> row:rows) {
            if (row.size()!=columns.size()) throw new IllegalArgumentException("Las filas no coinciden con las columnas del reporte.");
            List<Object> values=new ArrayList<>(row.size());
            for (Object value:row) {
                if (value==null || value instanceof Boolean || value instanceof String || value instanceof BigDecimal) values.add(value);
                else if (value instanceof Number) {
                    try {values.add(new BigDecimal(value.toString()));} catch (NumberFormatException error) {values.add(value.toString());}
                } else values.add(value.toString());
            }
            snapshot.add(Collections.unmodifiableList(values));
        }
        rows=Collections.unmodifiableList(snapshot);
    }
    public static ReportData empty() {return new ReportData("",List.of(),List.of(),false);}
    public boolean numeric(int column) {return rows.stream().anyMatch(row->row.get(column) instanceof Number);}
    @Override public String toString() {return name+" · "+rows.size()+" filas"+(limited?" · límite alcanzado":"");}
}
