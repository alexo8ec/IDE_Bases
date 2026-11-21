package com.nexodb.db;

import java.util.*;

/** In-memory workload profiler. It stores fingerprints, never literal values or full SQL. */
public final class QueryProfiler {
    public record Metric(String fingerprint,long samples,long activeSeconds,long maximumSeconds,long blockedSamples){public long averageSeconds(){return samples==0?0:activeSeconds/samples;}}
    private static final class Mutable {long samples,total,max,blocked;}
    private final LinkedHashMap<String,Mutable> values=new LinkedHashMap<>();
    public synchronized void sample(List<ServerActivity.Session> sessions){for(var session:sessions){if(session.query()==null||session.query().isBlank())continue;String fingerprint=fingerprint(session.query());Mutable value=values.computeIfAbsent(fingerprint,key->new Mutable());value.samples++;value.total+=Math.max(0,session.seconds());value.max=Math.max(value.max,session.seconds());if(!session.blockedBy().isBlank())value.blocked++;}while(values.size()>500)values.remove(values.keySet().iterator().next());}
    public synchronized List<Metric> metrics(){return values.entrySet().stream().map(e->new Metric(e.getKey(),e.getValue().samples,e.getValue().total,e.getValue().max,e.getValue().blocked)).sorted(Comparator.comparingLong(Metric::activeSeconds).reversed()).toList();}
    public synchronized void clear(){values.clear();}
    public static String fingerprint(String sql){
        StringBuilder result=new StringBuilder();boolean space=false;for(int i=0;i<sql.length();){char c=sql.charAt(i);
            if(c=='\''||c=='"'){char quote=c;i++;while(i<sql.length()){char x=sql.charAt(i++);if(x==quote){if(i<sql.length()&&sql.charAt(i)==quote)i++;else break;}else if(x=='\\'&&i<sql.length())i++;}append(result,"?");space=false;continue;}
            if(c=='-'&&i+1<sql.length()&&sql.charAt(i+1)=='-'||c=='#'){i=sql.indexOf('\n',i);if(i<0)break;space=true;continue;}
            if(c=='/'&&i+1<sql.length()&&sql.charAt(i+1)=='*'){int end=sql.indexOf("*/",i+2);i=end<0?sql.length():end+2;space=true;continue;}
            if(Character.isDigit(c)){i++;while(i<sql.length()&&(Character.isDigit(sql.charAt(i))||".xabcdefABCDEF".indexOf(sql.charAt(i))>=0))i++;append(result,"?");space=false;continue;}
            if(Character.isWhitespace(c)){space=true;i++;continue;}if(space&&result.length()>0&&result.charAt(result.length()-1)!=' ')result.append(' ');space=false;result.append(Character.toUpperCase(c));i++;
        }String value=result.toString().trim();return value.length()>240?value.substring(0,239)+"…":value;
    }
    private static void append(StringBuilder out,String value){if(out.length()==0||out.charAt(out.length()-1)!='?')out.append(value);}
}
