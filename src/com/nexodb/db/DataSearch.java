package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** SQLyog-style words, quoted phrases, OR and exclusions, emitted as bound LIKE conditions. */
public final class DataSearch {
    public record Condition(String sql,List<String> parameters){}
    private record Term(String value,boolean excluded){}
    private DataSearch(){}
    public static Condition condition(DatabaseType type,String quote,List<String> columns,String search){
        List<List<Term>> groups=new ArrayList<>();List<Term> group=new ArrayList<>();groups.add(group);
        var matcher=java.util.regex.Pattern.compile("(-?)\"([^\"]+)\"|([^\\s]+)").matcher(search);
        while(matcher.find()){String word=matcher.group(2)!=null?matcher.group(2):matcher.group(3);if(word.equalsIgnoreCase("OR")){if(!group.isEmpty()){group=new ArrayList<>();groups.add(group);}continue;}if(word.equalsIgnoreCase("AND"))continue;boolean excluded="-".equals(matcher.group(1))||word.startsWith("-");if(word.startsWith("-"))word=word.substring(1);if(!word.isBlank())group.add(new Term(word,excluded));}
        if(columns.isEmpty()||groups.stream().allMatch(List::isEmpty))throw new IllegalArgumentException("Introduce texto y selecciona al menos una columna.");List<String> parameters=new ArrayList<>(),ors=new ArrayList<>();
        for(List<Term> terms:groups){List<String> ands=new ArrayList<>();for(Term term:terms){List<String> likes=new ArrayList<>();for(String column:columns){String identifier=VisualQuery.quote(column,quote);String cast=switch(type){case MYSQL,MARIADB->"CAST("+identifier+" AS CHAR)";case SQLSERVER->"CAST("+identifier+" AS NVARCHAR(MAX))";case POSTGRESQL,SQLITE->"CAST("+identifier+" AS TEXT)";default->"CAST("+identifier+" AS VARCHAR(4000))";};likes.add("COALESCE("+cast+", '') LIKE ? ESCAPE '!'");parameters.add("%"+term.value().replace("!","!!").replace("%","!%").replace("_","!_")+"%");}ands.add((term.excluded()?"NOT ":"")+"("+String.join(" OR ",likes)+")");}if(!ands.isEmpty())ors.add("("+String.join(" AND ",ands)+")");}
        return new Condition(String.join(" OR ",ors),List.copyOf(parameters));
    }
    public static String literal(DatabaseType type,String value){if(type==DatabaseType.MYSQL||type==DatabaseType.MARIADB)return "CONVERT(X'"+HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8))+"' USING utf8mb4)";return "'"+value.replace("'","''")+"'";}
    public static String inline(DatabaseType type,Condition condition){String sql=condition.sql();List<SqlScriptTools.Token> placeholders=SqlScriptTools.tokens(sql).stream().filter(t->t.text().equals("?")&&sql.charAt(t.start())=='?').toList();if(placeholders.size()!=condition.parameters().size())throw new IllegalArgumentException("Invalid search placeholders");StringBuilder result=new StringBuilder(sql);for(int i=placeholders.size()-1;i>=0;i--){var token=placeholders.get(i);result.replace(token.start(),token.end(),literal(type,condition.parameters().get(i)));}return result.toString();}
}
