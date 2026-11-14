package com.nexodb.reporting;

import java.util.*;

/** Immutable, portable report template. Dimensions are in PDF/print points. */
public record ReportDesign(String title, boolean landscape, int headerHeight, String footer, int accent,
                           List<String> sourceColumns, List<Column> columns, List<Integer> groups, List<Block> blocks) {
    public enum Aggregate {NONE,COUNT,SUM,AVG,MIN,MAX;
        @Override public String toString() {return switch(this){case NONE->"Sin total";case COUNT->"Conteo";case SUM->"Suma";case AVG->"Promedio";case MIN->"Mínimo";case MAX->"Máximo";};}
    }
    public enum Kind {TEXT,METRIC,BAR_CHART;
        @Override public String toString() {return switch(this){case TEXT->"Texto";case METRIC->"Indicador";case BAR_CHART->"Gráfico de barras";};}
    }
    public record Column(int source, String title, int width, boolean visible, Aggregate aggregate) {
        public Column {Objects.requireNonNull(title);Objects.requireNonNull(aggregate);if(width<30||width>1000)throw new IllegalArgumentException("Ancho de columna fuera de rango.");}
    }
    public record Block(String id, Kind kind, int x, int y, int width, int height, String text, int fontSize,
                        boolean bold, int color, int field, Aggregate aggregate) {
        public Block {
            Objects.requireNonNull(id);Objects.requireNonNull(kind);Objects.requireNonNull(text);Objects.requireNonNull(aggregate);
            if(id.isBlank()||x<24||y<24||width<40||height<24||fontSize<8||fontSize>48||text.length()>20_000)
                throw new IllegalArgumentException("Bloque de reporte fuera de rango.");
            if(kind!=Kind.TEXT&&(aggregate==Aggregate.NONE||field<0&&aggregate!=Aggregate.COUNT))throw new IllegalArgumentException("El indicador o gráfico requiere una operación y un campo; Todas las filas utiliza Conteo.");
        }
        public Block bounds(int x,int y,int width,int height) {return new Block(id,kind,x,y,width,height,text,fontSize,bold,color,field,aggregate);}
    }
    public ReportDesign {
        Objects.requireNonNull(title);Objects.requireNonNull(footer);
        sourceColumns=List.copyOf(sourceColumns);columns=List.copyOf(columns);groups=List.copyOf(groups);blocks=List.copyOf(blocks);
        int pageWidth=landscape?842:595, pageHeight=landscape?595:842;
        if(title.length()>1000||footer.length()>1000||headerHeight<90||headerHeight>pageHeight-160||blocks.size()>100||groups.size()>5)
            throw new IllegalArgumentException("Diseño de reporte fuera de rango.");
        Set<Integer> fields=new HashSet<>();
        for(Column column:columns) if(column.source()<0||column.source()>=sourceColumns.size()||!fields.add(column.source()))throw new IllegalArgumentException("Columna de origen no válida o repetida.");
        if(!columns.isEmpty()&&columns.stream().noneMatch(Column::visible))throw new IllegalArgumentException("Debe haber al menos una columna visible.");
        if(new HashSet<>(groups).size()!=groups.size())throw new IllegalArgumentException("Agrupación no válida.");
        for(int group:groups)if(group<0||group>=sourceColumns.size())throw new IllegalArgumentException("Agrupación no válida.");
        Set<String> ids=new HashSet<>();
        for(Block b:blocks) if(!ids.add(b.id())||b.field() < -1 || b.field()>=sourceColumns.size()||b.x()+b.width()>pageWidth-24||b.y()+b.height()>headerHeight-8)
            throw new IllegalArgumentException("El bloque debe quedar dentro del encabezado. Amplía la banda o reduce su tamaño.");
    }
    public int pageWidth() {return landscape?842:595;}
    public int pageHeight() {return landscape?595:842;}
    public List<Column> visibleColumns() {return columns.stream().filter(Column::visible).toList();}
    public void requireCompatible(ReportData data) {if(!sourceColumns.equals(data.columns()))throw new IllegalArgumentException("La plantilla requiere las mismas columnas de origen, en el mismo orden. Selecciona un resultado compatible o crea un diseño nuevo.");}
    public ReportDesign withBlocks(List<Block> value) {return new ReportDesign(title,landscape,headerHeight,footer,accent,sourceColumns,columns,groups,value);}
    public ReportDesign replace(Block block) {return withBlocks(blocks.stream().map(b->b.id().equals(block.id())?block:b).toList());}
    public ReportDesign withColumns(List<Column> value) {return new ReportDesign(title,landscape,headerHeight,footer,accent,sourceColumns,value,groups,blocks);}
    public ReportDesign withGroups(List<Integer> value) {return new ReportDesign(title,landscape,headerHeight,footer,accent,sourceColumns,columns,value,blocks);}
    public static ReportDesign create(ReportData data) {
        List<Column> columns=new ArrayList<>();
        for(int i=0;i<data.columns().size();i++)columns.add(new Column(i,data.columns().get(i),120,i<8,Aggregate.NONE));
        return new ReportDesign("Reporte gerencial",false,190,"AdisDB",0x176B65,data.columns(),columns,List.of(),List.of(
                new Block(UUID.randomUUID().toString(),Kind.TEXT,32,32,530,48,"Reporte gerencial",24,true,0x173A40,-1,Aggregate.NONE),
                new Block(UUID.randomUUID().toString(),Kind.TEXT,32,88,530,32,"Resumen de resultados",12,false,0x607080,-1,Aggregate.NONE),
                new Block(UUID.randomUUID().toString(),Kind.METRIC,32,130,160,44,"Registros",18,true,0x176B65,-1,Aggregate.COUNT)));
    }
}
