package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.util.*;

/** A visual SELECT plan. Generating SQL never executes it. */
public final class VisualQuery {
    public record Table(String name,String alias){}
    public record Join(String left,String leftColumn,String right,String rightColumn,String type){}
    public record Field(String table,String column,boolean output,String aggregate,String alias,String sort,String criterion){}
    private VisualQuery(){}
    public static String quote(String name,String quote){quote=quote==null?"":quote.trim();if(quote.isEmpty()){if(!name.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*"))throw new IllegalArgumentException("El controlador no permite citar este identificador.");return name;}return quote+name.replace(quote,quote+quote)+quote;}
    public static String qualified(String database,String table,String quote){return (database==null||database.isBlank()?"":quote(database,quote)+".")+quote(table,quote);}
    private static String column(String table,String column,String quote){return quote(table,quote)+"."+quote(column,quote);}
    public static String select(DatabaseType type,String quote,String database,List<Table> tables,List<Join> joins,List<Field> fields,boolean distinct,int limit){
        if(tables.isEmpty())return "";Set<String> aliases=new HashSet<>();for(Table table:tables)if(!aliases.add(table.alias()))throw new IllegalArgumentException("Alias de tabla duplicado.");
        for(Join join:joins)if(!aliases.containsAll(List.of(join.left(),join.right()))||!Set.of("INNER","LEFT","RIGHT").contains(join.type()))throw new IllegalArgumentException("Relación no válida.");
        List<Field> output=fields.stream().filter(Field::output).toList();boolean grouped=output.stream().anyMatch(f->!f.aggregate().isBlank());
        StringBuilder sql=new StringBuilder("SELECT ");if(distinct)sql.append("DISTINCT ");if(type==DatabaseType.SQLSERVER&&limit>0)sql.append("TOP ").append(limit).append(' ');
        if(output.isEmpty())sql.append('*');else{List<String> columns=new ArrayList<>();for(Field f:output){if(!aliases.contains(f.table()))throw new IllegalArgumentException("Tabla de columna no disponible.");String expression=column(f.table(),f.column(),quote);if(!f.aggregate().isBlank()){if(!Set.of("COUNT","SUM","AVG","MIN","MAX").contains(f.aggregate()))throw new IllegalArgumentException("Agregado no válido.");expression=f.aggregate()+"("+expression+")";}if(!f.alias().isBlank())expression+=" AS "+quote(f.alias(),quote);columns.add(expression);}sql.append(String.join(",\n       ",columns));}
        Table first=tables.get(0);sql.append("\nFROM ").append(qualified(database,first.name(),quote)).append(" AS ").append(quote(first.alias(),quote));Set<String> added=new HashSet<>();added.add(first.alias());Set<Join> used=new HashSet<>();
        // Preserve the user's table order. Multiple conditions for the same join stay in its ON clause.
        for(int i=1;i<tables.size();i++){Table table=tables.get(i);List<Join> links=joins.stream().filter(j->j.right().equals(table.alias())&&added.contains(j.left())).toList();
            if(links.isEmpty())sql.append("\nCROSS JOIN ").append(qualified(database,table.name(),quote)).append(" AS ").append(quote(table.alias(),quote));
            else{sql.append('\n').append(links.get(0).type()).append(" JOIN ").append(qualified(database,table.name(),quote)).append(" AS ").append(quote(table.alias(),quote)).append(" ON ");sql.append(String.join(" AND ",links.stream().map(j->column(j.left(),j.leftColumn(),quote)+" = "+column(j.right(),j.rightColumn(),quote)).toList()));used.addAll(links);}added.add(table.alias());
        }
        List<String> where=new ArrayList<>();for(Join join:joins)if(!used.contains(join)){if(!join.type().equals("INNER"))throw new IllegalArgumentException("Agrega primero la tabla de origen de la relación externa.");where.add(column(join.left(),join.leftColumn(),quote)+" = "+column(join.right(),join.rightColumn(),quote));}
        for(Field field:fields)if(!field.criterion().isBlank()){if(field.criterion().indexOf(';')>=0)throw new IllegalArgumentException("El criterio no debe contener otra sentencia.");where.add(column(field.table(),field.column(),quote)+" "+field.criterion());}
        if(!where.isEmpty())sql.append("\nWHERE ").append(String.join("\n  AND ",where));
        if(grouped){List<String> group=output.stream().filter(f->f.aggregate().isBlank()).map(f->column(f.table(),f.column(),quote)).toList();if(!group.isEmpty())sql.append("\nGROUP BY ").append(String.join(", ",group));}
        List<String> order=fields.stream().filter(f->!f.sort().isBlank()).map(f->{if(!Set.of("ASC","DESC").contains(f.sort()))throw new IllegalArgumentException("Orden no válido.");String expr=column(f.table(),f.column(),quote);if(!f.aggregate().isBlank())expr=f.aggregate()+"("+expr+")";return expr+" "+f.sort();}).toList();if(!order.isEmpty())sql.append("\nORDER BY ").append(String.join(", ",order));
        if(limit>0&&type!=DatabaseType.SQLSERVER){if(type==DatabaseType.ORACLE)sql.append("\nFETCH FIRST ").append(limit).append(" ROWS ONLY");else sql.append("\nLIMIT ").append(limit);}return sql.append(';').toString();
    }
}
