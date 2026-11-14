package com.nexodb.db;

import java.io.*;
import java.util.*;

/** RFC 4180 records including quoted delimiters, doubled quotes, CRLF and multiline cells. */
public final class CsvReader implements AutoCloseable {
    private final PushbackReader input;private final char delimiter;private boolean first=true;private long record;
    public CsvReader(Reader input,char delimiter){this.input=new PushbackReader(new BufferedReader(input),2);this.delimiter=delimiter;}
    public long record(){return record;}
    public List<String> next()throws IOException{
        List<String> values=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false,closed=false,started=false;int total=0;
        for(int read;(read=input.read())!=-1;){char c=(char)read;if(first){first=false;if(c=='\uFEFF')continue;}started=true;if(++total>16*1024*1024)throw new IOException("Registro CSV mayor de 16 MB en fila "+(record+1));
            if(quoted){if(c=='"'){int next=input.read();if(next=='"')field.append('"');else{quoted=false;closed=true;if(next!=-1)input.unread(next);}}else field.append(c);continue;}
            if(c==delimiter){values.add(field.toString());field.setLength(0);closed=false;if(values.size()>10000)throw new IOException("Demasiadas columnas CSV");}
            else if(c=='\n'||c=='\r'){if(c=='\r'){int next=input.read();if(next!=-1&&next!='\n')input.unread(next);}values.add(field.toString());record++;return values;}
            else if(c=='"'&&field.isEmpty()&&!closed)quoted=true;
            else{if(closed)throw new IOException("Texto después de comillas CSV en fila "+(record+1));if(c=='"')throw new IOException("Comilla sin escapar en fila "+(record+1));field.append(c);}
        }
        if(quoted)throw new IOException("Comilla CSV sin cerrar en fila "+(record+1));if(!started&&values.isEmpty()&&field.isEmpty())return null;values.add(field.toString());record++;return values;
    }
    public void close()throws IOException{input.close();}
}
