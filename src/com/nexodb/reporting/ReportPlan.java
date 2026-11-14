package com.nexodb.reporting;

import java.math.*;
import java.util.*;
import static com.nexodb.reporting.ReportDesign.Aggregate;

/** Shared grouping/aggregation plan consumed by preview and every export format. */
public final class ReportPlan {
    public enum Kind {GROUP,DETAIL,SUBTOTAL,TOTAL}
    public record Line(Kind kind,int level,String label,List<Object> values) {
        public Line {values=Collections.unmodifiableList(new ArrayList<>(values));}
    }
    public record Bar(String label,BigDecimal value) {}
    private final ReportDesign design;
    private final ReportData data;
    private final List<Line> lines;
    private final Map<String,Object> metrics=new HashMap<>();
    private final Map<String,List<Bar>> charts=new HashMap<>();
    private ReportPlan(ReportDesign design,ReportData data,List<Line> lines){this.design=design;this.data=data;this.lines=List.copyOf(lines);}
    public static ReportPlan build(ReportDesign design,ReportData data) {
        design.requireCompatible(data);
        List<Line> lines=new ArrayList<>();
        append(design,data,data.rows(),0,lines);
        if(!data.columns().isEmpty()) lines.add(new Line(Kind.TOTAL,0,"Total general · "+data.rows().size()+" registros",totals(design,data.rows())));
        var plan=new ReportPlan(design,data,lines);for(var block:design.blocks()){if(block.kind()==ReportDesign.Kind.METRIC)plan.metric(block);else if(block.kind()==ReportDesign.Kind.BAR_CHART)plan.bars(block);}return plan;
    }
    public ReportDesign design(){return design;}
    public ReportData data(){return data;}
    public List<Line> lines(){return lines;}
    private static void append(ReportDesign design,ReportData data,List<List<Object>> rows,int level,List<Line> out) {
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
        if(level==design.groups().size()) {
            var visible=design.visibleColumns();for(var row:rows)out.add(new Line(Kind.DETAIL,level,"",visible.stream().map(c->row.get(c.source())).toList()));
            return;
        }
        int field=design.groups().get(level);
        Map<Object,List<List<Object>>> groups=new LinkedHashMap<>();
        for(var row:rows)groups.computeIfAbsent(key(row.get(field)),ignored->new ArrayList<>()).add(row);
        for(var entry:groups.entrySet()) {
            String value=display(entry.getKey());
            out.add(new Line(Kind.GROUP,level,data.columns().get(field)+": "+value,Collections.nCopies(design.visibleColumns().size(),null)));
            append(design,data,entry.getValue(),level+1,out);
            out.add(new Line(Kind.SUBTOTAL,level,"Subtotal · "+value+" · "+entry.getValue().size()+" registros",totals(design,entry.getValue())));
        }
    }
    private static Object key(Object value){return value instanceof BigDecimal number?number.stripTrailingZeros():value;}
    private static List<Object> totals(ReportDesign design,List<List<Object>> rows){return design.visibleColumns().stream().map(c->aggregate(rows,c.source(),c.aggregate())).toList();}
    public static Object aggregate(List<List<Object>> rows,int field,Aggregate operation) {
        if(operation==Aggregate.NONE)return null;
        if(operation==Aggregate.COUNT)return (long)(field<0?rows.size():rows.stream().filter(row->row.get(field)!=null).count());
        BigDecimal total=BigDecimal.ZERO,min=null,max=null;int count=0;
        if(field<0)return null;
        for(var row:rows)if(row.get(field) instanceof Number number) {
            BigDecimal value;
            try{value=new BigDecimal(number.toString());}catch(NumberFormatException error){continue;}
            total=total.add(value);min=min==null?value:min.min(value);max=max==null?value:max.max(value);count++;
        }
        if(count==0)return null;
        return switch(operation){case SUM->total;case AVG->total.divide(BigDecimal.valueOf(count),MathContext.DECIMAL128);case MIN->min;case MAX->max;default->null;};
    }
    public synchronized Object metric(ReportDesign.Block block){String key=block.field()+":"+block.aggregate().name();if(!metrics.containsKey(key))metrics.put(key,aggregate(data.rows(),block.field(),block.aggregate()));return metrics.get(key);}
    /** Category counts when no numeric field is chosen; otherwise the selected aggregation. */
    public synchronized List<Bar> bars(ReportDesign.Block block) {
        String cacheKey=block.field()+":"+block.aggregate().name();if(charts.containsKey(cacheKey))return charts.get(cacheKey);
        int category=design.groups().isEmpty()?(data.columns().isEmpty()?-1:0):design.groups().get(0);
        if(category<0)return List.of();
        Map<Object,List<List<Object>>> groups=new LinkedHashMap<>();
        for(var row:data.rows())groups.computeIfAbsent(key(row.get(category)),ignored->new ArrayList<>()).add(row);
        List<Bar> bars=new ArrayList<>();
        for(var entry:groups.entrySet()) {
            Object amount=aggregate(entry.getValue(),block.field(),block.aggregate()==Aggregate.NONE?Aggregate.COUNT:block.aggregate());
            if(amount instanceof Number n)bars.add(new Bar(display(entry.getKey()),new BigDecimal(n.toString())));
        }
        bars.sort(Comparator.comparing((Bar b)->b.value().abs()).reversed());
        if(bars.size()<=8){var result=List.copyOf(bars);charts.put(cacheKey,result);return result;}
        // Keep the sum of additive charts; averages/min/max are never summed into a misleading "Other".
        var result=List.copyOf(bars.subList(0,8));charts.put(cacheKey,result);return result;
    }
    public String sourceNote(){return "Fuente: "+data.name()+" · "+data.rows().size()+" filas"+(data.limited()?" · resultado limitado por la consulta":"");}
    public ReportData tabular() {
        List<String> headers=new ArrayList<>(List.of("Sección","Agrupación"));
        design.visibleColumns().forEach(c->headers.add(c.title()));
        List<List<Object>> rows=new ArrayList<>();
        for(Line line:lines) {
            List<Object> row=new ArrayList<>();row.add(switch(line.kind()){case GROUP->"Encabezado";case DETAIL->"Detalle";case SUBTOTAL->"Subtotal";case TOTAL->"Total general";});row.add(line.label());row.addAll(line.values());rows.add(row);
        }
        return new ReportData(design.title(),headers,rows,data.limited());
    }
    public static String display(Object value) {
        if(value==null)return "(NULL)";
        if(value instanceof BigDecimal n){var stripped=n.stripTrailingZeros();return stripped.scale()<0?stripped.setScale(0).toPlainString():stripped.toPlainString();}
        return value.toString();
    }
}
