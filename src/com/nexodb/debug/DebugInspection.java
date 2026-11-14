package com.nexodb.debug;

import com.nexodb.db.QueryResult;
import com.nexodb.util.Json;
import java.util.*;

/** Inspection uses only snapshots already received from the paused server; hovering executes no SQL. */
public final class DebugInspection {
    private DebugInspection(){}
    public static MysqlDebugSession.Value valueAt(List<MysqlProcedure.Token> tokens,int offset,List<MysqlDebugSession.Value> values){
        for(int i=0;i<tokens.size();i++){var token=tokens.get(i);if(offset<token.start()||offset>=token.end())continue;if(token.literal())return null;
            boolean user=i>0&&tokens.get(i-1).is("@");if(user&&i>1&&tokens.get(i-2).is("@"))return null;
            String name=(user?"@":"")+token.value();return values.stream().filter(v->v.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        }return null;
    }
    public static QueryResult table(MysqlDebugSession.Value value){
        if(value==null||value.isNull()||value.truncated()||value.value()==null)return null;String text=value.value().strip();if(!text.startsWith("[")&&!text.startsWith("{"))return null;
        try{
            Object parsed=Json.object("{\"data\":"+text+"}").get("data");List<?> records=parsed instanceof List<?> list?list:List.of(parsed);if(records.isEmpty())return new QueryResult(List.of("valor"),List.of(),-1,0,false);
            Set<String> keys=new LinkedHashSet<>();for(Object record:records)if(record instanceof Map<?,?> map)map.keySet().forEach(k->keys.add(k.toString()));if(keys.size()>128)return null;List<String> columns=keys.isEmpty()?List.of("valor"):List.copyOf(keys);List<List<Object>> rows=new ArrayList<>();
            for(Object record:records){List<Object> row=new ArrayList<>();for(String column:columns){Object cell=record instanceof Map<?,?> map?map.get(column):record;row.add(cell instanceof Map<?,?>||cell instanceof List<?>?Json.stringify(cell):cell);}rows.add(Collections.unmodifiableList(row));}
            return new QueryResult(columns,List.copyOf(rows),-1,0,false);
        }catch(IllegalArgumentException invalid){return null;}
    }
}
