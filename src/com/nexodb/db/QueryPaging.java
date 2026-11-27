package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import com.nexodb.util.SqlText;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Paging replays one read query only. CALL/EXEC and multi-statement batches are never replayed. */
public final class QueryPaging {
    public record PageSql(String sql,boolean nativeOffset) {}
    public record Order(int column,boolean descending) {}
    public record OrderAnalysis(boolean present,List<Order> orders) {
        public OrderAnalysis { orders=List.copyOf(orders); }
        public boolean supported(){return !present||!orders.isEmpty();}
    }
    public record SeekSql(String sql,List<Object> parameters) {
        public SeekSql { parameters=List.copyOf(parameters); }
    }
    private QueryPaging() {}
    public static boolean supported(String sql) {
        try {
            var statements=SqlText.executionStatements(sql);
            if(statements.size()!=1||!SqlExecutionPolicy.readOnly(sql)||SqlExecutionPolicy.referencesSessionVariables(sql))return false;
            var tokens=SqlScriptTools.tokens(sql);
            return !tokens.isEmpty()&&(tokens.get(0).is("SELECT")||tokens.get(0).is("WITH"));
        }catch(java.io.IOException error){return false;}
    }
    public static void validate(String sql,int size,long offset) {
        if(!supported(sql))throw new IllegalArgumentException("La paginación requiere una única consulta SELECT o WITH de lectura.");
        if(size<1||size>10_000||offset<0)throw new IllegalArgumentException("El tamaño de página debe estar entre 1 y 10.000 filas.");
    }

    /** Adds server-side paging only when it is safe to preserve the original query semantics. */
    public static PageSql pageSql(String sql,DatabaseType type,int size,long offset){
        validate(sql,size,offset);
        try{
            String statement=SqlText.executionStatements(sql).get(0).strip();
            List<SqlScriptTools.Token> tokens=SqlScriptTools.tokens(statement);
            List<String> top=topLevelWords(tokens);
            if(top.stream().anyMatch(java.util.Set.of("LIMIT","OFFSET","FETCH","TOP")::contains))return new PageSql(statement,false);
            long requested=(long)size+1L;
            return switch(type){
                case MYSQL,MARIADB,POSTGRESQL,SQLITE,H2 -> new PageSql(statement+" LIMIT "+requested+" OFFSET "+offset,true);
                case SQLSERVER,ORACLE -> containsSequence(top,"ORDER","BY")
                        ?new PageSql(statement+" OFFSET "+offset+" ROWS FETCH NEXT "+requested+" ROWS ONLY",true)
                        :new PageSql(statement,false);
                default -> new PageSql(statement,false);
            };
        }catch(Exception unavailable){return new PageSql(sql,false);}
    }

    /** Reads a simple top-level ORDER BY and maps its fields to visible result columns. */
    public static OrderAnalysis order(String sql,List<String> columns){
        try{
            String statement=SqlText.executionStatements(sql).get(0).strip();
            List<SqlScriptTools.Token> tokens=SqlScriptTools.tokens(statement);int depth=0,start=-1;
            for(int i=0;i+1<tokens.size();i++){
                SqlScriptTools.Token token=tokens.get(i);if(token.is("(")){depth++;continue;}if(token.is(")")){depth=Math.max(0,depth-1);continue;}
                if(depth==0&&token.is("ORDER")&&tokens.get(i+1).is("BY")){start=i+2;break;}
            }
            if(start<0)return new OrderAnalysis(false,List.of());
            List<List<SqlScriptTools.Token>> terms=new ArrayList<>();List<SqlScriptTools.Token> current=new ArrayList<>();depth=0;
            for(int i=start;i<tokens.size();i++){
                SqlScriptTools.Token token=tokens.get(i);
                if(depth==0&&Set.of("LIMIT","OFFSET","FETCH","FOR").stream().anyMatch(token::is))break;
                if(token.is("("))depth++;else if(token.is(")"))depth=Math.max(0,depth-1);
                if(depth==0&&token.is(",")){if(current.isEmpty())return new OrderAnalysis(true,List.of());terms.add(List.copyOf(current));current.clear();}
                else current.add(token);
            }
            if(!current.isEmpty())terms.add(List.copyOf(current));if(terms.isEmpty())return new OrderAnalysis(true,List.of());
            List<Order> result=new ArrayList<>();Set<Integer> used=new LinkedHashSet<>();
            for(List<SqlScriptTools.Token> raw:terms){
                List<SqlScriptTools.Token> term=new ArrayList<>(raw);boolean descending=false;
                if(!term.isEmpty()&&(term.get(term.size()-1).is("ASC")||term.get(term.size()-1).is("DESC"))){descending=term.remove(term.size()-1).is("DESC");}
                int column=-1;
                if(term.size()==1&&term.get(0).text().matches("[0-9]+")){int ordinal=Integer.parseInt(term.get(0).text());if(ordinal>=1&&ordinal<=columns.size())column=ordinal-1;}
                else if(term.size()==1&&term.get(0).name())column=column(columns,term.get(0).text());
                else if(term.size()==3&&term.get(0).name()&&term.get(1).is(".")&&term.get(2).name())column=column(columns,term.get(2).text());
                if(column<0||!used.add(column))return new OrderAnalysis(true,List.of());result.add(new Order(column,descending));
            }
            return new OrderAnalysis(true,result);
        }catch(Exception unavailable){return new OrderAnalysis(true,List.of());}
    }

    /** Builds a parameterized seek page. It never changes queries with their own paging clause. */
    public static SeekSql seekSql(String sql,DatabaseType type,int size,List<String> columns,List<Order> order,List<?> baseParameters,List<?> anchor){
        validate(sql,size,0);Objects.requireNonNull(type);Objects.requireNonNull(columns);Objects.requireNonNull(order);Objects.requireNonNull(baseParameters);Objects.requireNonNull(anchor);
        if(type==DatabaseType.GENERIC||columns.isEmpty()||order.isEmpty())throw new IllegalArgumentException("La consulta no tiene un orden estable para paginar por clave.");
        Set<String> unique=new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);if(columns.stream().anyMatch(name->name==null||name.isBlank()||!unique.add(name)))throw new IllegalArgumentException("Las columnas del resultado no son únicas.");
        if(!anchor.isEmpty()&&anchor.size()!=order.size())throw new IllegalArgumentException("El cursor de paginación no coincide con el orden.");
        if(anchor.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Una clave nula requiere paginación compatible por desplazamiento.");
        for(Order item:order)if(item.column()<0||item.column()>=columns.size())throw new IllegalArgumentException("El orden no pertenece al resultado.");
        try{
            String base=SqlText.executionStatements(sql).get(0).strip();List<SqlScriptTools.Token> tokens=SqlScriptTools.tokens(base);List<String> top=topLevelWords(tokens);
            if(top.stream().anyMatch(Set.of("LIMIT","OFFSET","FETCH","TOP")::contains))throw new IllegalArgumentException("La consulta ya contiene su propio límite o desplazamiento.");
            if(type==DatabaseType.SQLSERVER&&tokens.get(0).is("WITH"))throw new IllegalArgumentException("SQL Server no permite envolver este CTE de forma segura.");
            if(type==DatabaseType.SQLSERVER&&containsSequence(top,"ORDER","BY"))base+=" OFFSET 0 ROWS";
            String alias=quote(type,"__adisdb_page");List<Object> parameters=new ArrayList<>(baseParameters);StringBuilder result=new StringBuilder("SELECT * FROM (").append(base).append(") ").append(alias);
            if(!anchor.isEmpty()){
                List<String> choices=new ArrayList<>();
                for(int i=0;i<order.size();i++){
                    List<String> part=new ArrayList<>();
                    for(int equal=0;equal<i;equal++){part.add(field(type,alias,columns.get(order.get(equal).column()))+" = ?");parameters.add(anchor.get(equal));}
                    Order item=order.get(i);part.add(field(type,alias,columns.get(item.column()))+(item.descending()?" < ?":" > ?"));parameters.add(anchor.get(i));choices.add("("+String.join(" AND ",part)+")");
                }
                result.append(" WHERE (").append(String.join(" OR ",choices)).append(')');
            }
            List<String> ordering=new ArrayList<>();for(Order item:order)ordering.add(field(type,alias,columns.get(item.column()))+(item.descending()?" DESC":" ASC"));
            result.append(" ORDER BY ").append(String.join(", ",ordering));long requested=(long)size+1L;
            switch(type){case SQLSERVER,ORACLE->result.append(" OFFSET 0 ROWS FETCH NEXT ").append(requested).append(" ROWS ONLY");default->result.append(" LIMIT ").append(requested);}
            return new SeekSql(result.toString(),parameters);
        }catch(java.io.IOException error){throw new IllegalArgumentException("No se pudo preparar la página por clave.",error);}
    }

    /** Wraps one safe read query for an exact asynchronous count. */
    public static String countSql(String sql,DatabaseType type){
        validate(sql,1,0);if(type==DatabaseType.GENERIC)throw new IllegalArgumentException("El motor no admite un conteo envuelto seguro.");
        try{
            String statement=SqlText.executionStatements(sql).get(0).strip();List<SqlScriptTools.Token> tokens=SqlScriptTools.tokens(statement);List<String> top=topLevelWords(tokens);
            if(type==DatabaseType.SQLSERVER&&!tokens.isEmpty()&&tokens.get(0).is("WITH"))throw new IllegalArgumentException("El CTE de SQL Server no puede envolverse de forma segura.");
            boolean paged=top.stream().anyMatch(Set.of("LIMIT","OFFSET","FETCH","TOP")::contains);int depth=0,orderStart=-1;
            for(int i=0;i+1<tokens.size();i++){SqlScriptTools.Token token=tokens.get(i);if(token.is("(")){depth++;continue;}if(token.is(")")){depth=Math.max(0,depth-1);continue;}if(depth==0&&token.is("ORDER")&&tokens.get(i+1).is("BY")){orderStart=token.start();break;}}
            if(orderStart>=0&&!paged)statement=statement.substring(0,orderStart).stripTrailing();
            return "SELECT COUNT(*) FROM ("+statement+") "+quote(type,"__adisdb_count");
        }catch(java.io.IOException error){throw new IllegalArgumentException("No se pudo preparar el conteo exacto.",error);}
    }

    /** Simple selects are counted automatically; complex shapes require an explicit confirmation. */
    public static boolean cheapCount(String sql){
        try{List<String> words=topLevelWords(SqlScriptTools.tokens(SqlText.executionStatements(sql).get(0)));return words.stream().noneMatch(Set.of("DISTINCT","GROUP","HAVING","UNION","INTERSECT","EXCEPT","LIMIT","OFFSET","FETCH","TOP")::contains);}
        catch(Exception unavailable){return false;}
    }

    public static List<Order> reverse(List<Order> order){return order.stream().map(item->new Order(item.column(),!item.descending())).toList();}

    public static boolean comparable(int jdbcType){return switch(jdbcType){
        case Types.BIT,Types.BOOLEAN,Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.REAL,Types.FLOAT,Types.DOUBLE,Types.NUMERIC,Types.DECIMAL,
                Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR,Types.DATE,Types.TIME,Types.TIME_WITH_TIMEZONE,Types.TIMESTAMP,Types.TIMESTAMP_WITH_TIMEZONE->true;
        default->false;
    };}

    private static int column(List<String> columns,String name){int found=-1;for(int i=0;i<columns.size();i++)if(name.equalsIgnoreCase(columns.get(i))){if(found>=0)return -1;found=i;}return found;}
    private static String field(DatabaseType type,String alias,String column){return alias+"."+quote(type,column);}
    private static String quote(DatabaseType type,String value){return switch(type){case MYSQL,MARIADB->"`"+value.replace("`","``")+"`";case SQLSERVER->"["+value.replace("]","]]")+"]";default->"\""+value.replace("\"","\"\"")+"\"";};}

    private static List<String> topLevelWords(List<SqlScriptTools.Token> tokens){
        List<String> words=new java.util.ArrayList<>();int depth=0;
        for(SqlScriptTools.Token token:tokens){
            if(token.is("(")){depth++;continue;}if(token.is(")")){depth=Math.max(0,depth-1);continue;}
            if(depth==0&&token.name())words.add(token.text().toUpperCase(java.util.Locale.ROOT));
        }
        return words;
    }
    private static boolean containsSequence(List<String> words,String first,String second){
        for(int i=0;i+1<words.size();i++)if(first.equals(words.get(i))&&second.equals(words.get(i+1)))return true;
        return false;
    }
}
