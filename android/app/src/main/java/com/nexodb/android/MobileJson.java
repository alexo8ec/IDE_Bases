package com.nexodb.android;

import org.json.*;
import java.lang.reflect.*;
import java.util.*;

final class MobileJson {
    private MobileJson(){}
    static Object value(Object input)throws Exception{
        if(input==null)return JSONObject.NULL;if(input instanceof JSONObject||input instanceof JSONArray||input instanceof String||input instanceof Boolean)return input;
        if(input instanceof java.math.BigDecimal||input instanceof java.math.BigInteger)return input.toString();if(input instanceof Number)return input;if(input instanceof Enum<?> e)return e.name();
        if(input instanceof Map<?,?> map){JSONObject object=new JSONObject();for(var entry:map.entrySet())object.put(entry.getKey().toString(),value(entry.getValue()));return object;}
        if(input instanceof Iterable<?> values){JSONArray array=new JSONArray();for(Object item:values)array.put(value(item));return array;}
        if(input.getClass().getName().startsWith("com.nexodb.")){JSONObject object=new JSONObject();for(Field field:input.getClass().getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())&&!field.isSynthetic()){field.setAccessible(true);object.put(field.getName(),value(field.get(input)));}return object;}
        return input.toString();
    }
    static JSONObject object(Object input)throws Exception{return (JSONObject)value(input);}
    static Object nullable(JSONObject object,String key){Object value=object.opt(key);return value==JSONObject.NULL?null:value;}
    static String string(JSONObject object,String key){return object.optString(key,"");}
}
