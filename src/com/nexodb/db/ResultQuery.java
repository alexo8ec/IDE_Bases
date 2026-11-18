package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import com.nexodb.util.SqlText;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.*;

/** Builds a parameterized outer view for global filtering and ordering of a read-only result. */
public final class ResultQuery {
    public enum Operator { EXACT,EQUALS,NOT_EQUALS,CONTAINS,NOT_CONTAINS,STARTS_WITH,ENDS_WITH,GREATER,GREATER_OR_EQUAL,LESS,LESS_OR_EQUAL,IS_NULL,IS_NOT_NULL,IS_EMPTY,IS_NOT_EMPTY }
    public enum Junction { AND,OR }
    public record Condition(Operator operator,Object value) {}
    public record Filter(int column,Condition first,Junction junction,Condition second,boolean caseSensitive) {}
    public record Sort(int column,boolean descending) {}
    public record Prepared(String sql,List<Object> parameters) { public Prepared {parameters=List.copyOf(parameters);} }
    private ResultQuery() {}

    public static boolean supported(String sql,DatabaseType type,List<String> columns){
        if(!QueryPaging.supported(sql)||type==DatabaseType.GENERIC||columns.isEmpty())return false;
        Set<String> unique=new TreeSet<>(String.CASE_INSENSITIVE_ORDER);if(columns.stream().anyMatch(name->name==null||name.isBlank()||!unique.add(name)))return false;
        try{
            List<SqlScriptTools.Token> tokens=SqlScriptTools.tokens(SqlText.executionStatements(sql).get(0));
            List<String> top=topLevel(tokens);
            return type!=DatabaseType.SQLSERVER||!tokens.get(0).is("WITH")&&!top.contains("OPTION");
        }catch(Exception error){return false;}
    }

    public static Prepared prepare(String sql,DatabaseType type,List<String> columns,List<Integer> jdbcTypes,String global,List<Filter> filters,List<Sort> sorts){
        if(!supported(sql,type,columns))throw new IllegalArgumentException("La consulta no admite filtros globales seguros.");
        if(Objects.toString(global,"").isBlank()&&filters.isEmpty()&&sorts.isEmpty())return new Prepared(sql,List.of());
        try{
            String base=SqlText.executionStatements(sql).get(0).strip();List<String> top=topLevel(SqlScriptTools.tokens(base));
            if(type==DatabaseType.SQLSERVER&&containsSequence(top,"ORDER","BY")&&top.stream().noneMatch(Set.of("TOP","OFFSET","FETCH")::contains))base+=" OFFSET 0 ROWS";
            String alias=quote(type,"__adisdb_result");StringBuilder result=new StringBuilder("SELECT * FROM (").append(base).append(") ").append(alias);
            List<Object> parameters=new ArrayList<>();List<String> predicates=new ArrayList<>();
            String term=Objects.toString(global,"").trim();
            if(!term.isEmpty()){
                List<String> any=new ArrayList<>();String pattern="%"+like(term.toLowerCase(Locale.ROOT))+"%";
                for(String column:columns){any.add("LOWER("+text(type,alias+"."+quote(type,column))+") LIKE ? ESCAPE '!'");parameters.add(pattern);}
                predicates.add("("+String.join(" OR ",any)+")");
            }
            for(Filter filter:filters){
                if(filter.column()<0||filter.column()>=columns.size()||filter.first()==null)continue;
                String column=alias+"."+quote(type,columns.get(filter.column()));int jdbcType=filter.column()<jdbcTypes.size()?jdbcTypes.get(filter.column()):Types.OTHER;
                String first=condition(type,column,jdbcType,filter.first(),filter.caseSensitive(),parameters);
                if(filter.second()==null)predicates.add(first);else predicates.add("("+first+" "+filter.junction()+" "+condition(type,column,jdbcType,filter.second(),filter.caseSensitive(),parameters)+")");
            }
            if(!predicates.isEmpty())result.append(" WHERE ").append(String.join(" AND ",predicates));
            List<String> order=new ArrayList<>();for(Sort sort:sorts)if(sort.column()>=0&&sort.column()<columns.size())order.add(alias+"."+quote(type,columns.get(sort.column()))+(sort.descending()?" DESC":" ASC"));
            if(!order.isEmpty())result.append(" ORDER BY ").append(String.join(", ",order));
            return new Prepared(result.toString(),parameters);
        }catch(java.io.IOException error){throw new IllegalArgumentException("No se pudo preparar la consulta filtrada.",error);}
    }

    private static String condition(DatabaseType type,String column,int jdbcType,Condition condition,boolean caseSensitive,List<Object> parameters){
        Operator operator=condition.operator();if(operator==Operator.IS_NULL)return column+" IS NULL";if(operator==Operator.IS_NOT_NULL)return column+" IS NOT NULL";
        String cast=text(type,column);if(operator==Operator.IS_EMPTY)return cast+" = ''";if(operator==Operator.IS_NOT_EMPTY)return cast+" <> ''";
        Object value=condition.value();boolean numeric=numeric(jdbcType)&&value!=null;
        if(numeric)try{value=new BigDecimal(value.toString().trim());}catch(NumberFormatException ignored){numeric=false;}
        boolean like=Set.of(Operator.CONTAINS,Operator.NOT_CONTAINS,Operator.STARTS_WITH,Operator.ENDS_WITH).contains(operator);
        String expression=numeric?column:cast;Object parameter=value;
        if(like){String raw=Objects.toString(value,"");parameter=switch(operator){case CONTAINS,NOT_CONTAINS->"%"+like(raw)+"%";case STARTS_WITH->like(raw)+"%";case ENDS_WITH->"%"+like(raw);default->raw;};}
        if(!caseSensitive&&!numeric){expression="LOWER("+expression+")";parameter=Objects.toString(parameter,"").toLowerCase(Locale.ROOT);}
        parameters.add(parameter);
        return switch(operator){
            case EXACT,EQUALS->expression+" = ?";case NOT_EQUALS->expression+" <> ?";
            case CONTAINS,STARTS_WITH,ENDS_WITH->expression+" LIKE ? ESCAPE '!'";case NOT_CONTAINS->expression+" NOT LIKE ? ESCAPE '!'";
            case GREATER->expression+" > ?";case GREATER_OR_EQUAL->expression+" >= ?";case LESS->expression+" < ?";case LESS_OR_EQUAL->expression+" <= ?";
            default->throw new IllegalArgumentException("Operador de filtro no válido.");
        };
    }
    private static boolean numeric(int type){return switch(type){case Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.REAL,Types.FLOAT,Types.DOUBLE,Types.NUMERIC,Types.DECIMAL->true;default->false;};}
    private static String like(String value){return value.replace("!","!!").replace("%","!%").replace("_","!_");}
    private static String text(DatabaseType type,String expression){return switch(type){case MYSQL,MARIADB->"CAST("+expression+" AS CHAR)";case POSTGRESQL,SQLITE->"CAST("+expression+" AS TEXT)";case SQLSERVER->"CAST("+expression+" AS NVARCHAR(MAX))";case ORACLE->"CAST("+expression+" AS VARCHAR2(4000))";default->"CAST("+expression+" AS VARCHAR(4000))";};}
    private static String quote(DatabaseType type,String value){return switch(type){case MYSQL,MARIADB->"`"+value.replace("`","``")+"`";case SQLSERVER->"["+value.replace("]","]]")+"]";default->"\""+value.replace("\"","\"\"")+"\"";};}
    private static List<String> topLevel(List<SqlScriptTools.Token> tokens){List<String> words=new ArrayList<>();int depth=0;for(var token:tokens){if(token.is("(")){depth++;continue;}if(token.is(")")){depth=Math.max(0,depth-1);continue;}if(depth==0&&token.name())words.add(token.text().toUpperCase(Locale.ROOT));}return words;}
    private static boolean containsSequence(List<String> words,String first,String second){for(int i=0;i+1<words.size();i++)if(first.equals(words.get(i))&&second.equals(words.get(i+1)))return true;return false;}
}
