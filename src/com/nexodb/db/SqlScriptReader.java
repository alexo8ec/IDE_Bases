package com.nexodb.db;

import java.io.*;
import java.util.regex.*;

/** Streaming SQL statements, including mysql/SQLyog DELIMITER and quoted routine bodies. */
public final class SqlScriptReader implements AutoCloseable {
    public record Statement(String sql,int line) {}
    private final PushbackReader input;
    private String delimiter=";", dollar;
    private int line=1,state;
    private boolean lineStart=true,noBackslash;
    private final java.util.Map<String,Boolean> savedModes=new java.util.HashMap<>();
    public SqlScriptReader(Reader reader){input=new PushbackReader(new BufferedReader(reader),256);}
    public SqlScriptReader(Reader reader,String sqlMode){this(reader);noBackslash=sqlMode!=null&&sqlMode.toUpperCase(java.util.Locale.ROOT).contains("NO_BACKSLASH_ESCAPES");}
    private boolean follows(String text)throws IOException {
        char[] chars=new char[text.length()];int count=0;
        while(count<chars.length){int read=input.read(chars,count,chars.length-count);if(read<0)break;count+=read;}
        if(count>0)input.unread(chars,0,count);return count==text.length()&&new String(chars).equals(text);
    }
    private boolean followsIgnoreCase(String text)throws IOException{char[] chars=new char[text.length()];int count=0;while(count<chars.length){int read=input.read(chars,count,chars.length-count);if(read<0)break;count+=read;}if(count>0)input.unread(chars,0,count);return count==text.length()&&new String(chars).equalsIgnoreCase(text);}
    private boolean dashComment()throws IOException{char[] peek=new char[2];int count=0;while(count<2){int read=input.read(peek,count,2-count);if(read<0)break;count+=read;}if(count>0)input.unread(peek,0,count);return count>0&&peek[0]=='-'&&(count==1||Character.isWhitespace(peek[1])||Character.isISOControl(peek[1]));}
    private void consume(int count)throws IOException{while(count-->0)input.read();}
    public Statement next()throws IOException {
        StringBuilder sql=new StringBuilder();boolean code=false;int start=line;
        for(int value;(value=input.read())!=-1;) {
            char c=(char)value;
            if(c=='\uFEFF'&&line==1&&sql.isEmpty()&&state==0)continue;
            if(sql.length()>32*1024*1024)throw new IOException("Una sentencia supera 32 MB. Genera INSERT más pequeños para importar este archivo.");
            if(state==0&&lineStart&&!code&&(c=='D'||c=='d')&&followsIgnoreCase("ELIMITER")) {
                consume(8);StringBuilder rest=new StringBuilder();int next;while((next=input.read())!=-1&&next!='\n')rest.append((char)next);
                String configured=rest.toString().trim();if(configured.isEmpty()||configured.length()>32||configured.chars().anyMatch(Character::isWhitespace))throw new IOException("DELIMITER no válido en la línea "+line);
                delimiter=configured;sql.setLength(0);if(next=='\n')line++;lineStart=true;start=line;continue;
            }
            if(state==0&&c==delimiter.charAt(0)&&follows(delimiter.substring(1))) {
                consume(delimiter.length()-1);String statement=sql.toString().trim();lineStart=false;
                if(code){updateMode(statement);return new Statement(statement,start);}sql.setLength(0);start=line;continue;
            }
            if(!code&&!Character.isWhitespace(c)&&state==0)start=line;
            sql.append(c);
            if(state==5){if(c=='\n'||c=='\r')state=0;}
            else if(state==6){if(c=='*'&&follows("/")){consume(1);sql.append('/');state=0;}}
            else if(state==7){if(c=='$'&&follows(dollar.substring(1))){consume(dollar.length()-1);sql.append(dollar.substring(1));state=0;dollar=null;}}
            else if(state>0){char quote=state==1?'\'':state==2?'"':state==3?'`':']';if(c=='\\'&&!noBackslash&&(state==1||state==2)){int escaped=input.read();if(escaped<0)throw new IOException("Texto SQL incompleto en la línea "+line);sql.append((char)escaped);if(escaped=='\n')line++;}else if(c==quote){if(follows(String.valueOf(quote))){consume(1);sql.append(quote);}else state=0;}}
            else {
                if(c=='#')state=5;
                else if(c=='-'&&dashComment()){consume(1);sql.append('-');state=5;}
                else if(c=='/'&&follows("*")){consume(1);sql.append('*');state=6;if(follows("!")||follows("M!"))code=true;}
                else if(c=='\''||c=='"'||c=='`'||c=='['){state=c=='\''?1:c=='"'?2:c=='`'?3:4;code=true;}
                else if(c=='$'&&delimiter.equals(";")){StringBuilder tag=new StringBuilder("$");int read;while(tag.length()<64&&(read=input.read())!=-1){char t=(char)read;if(t=='$'){tag.append(t);break;}if(!Character.isLetterOrDigit(t)&&t!='_'){input.unread(t);break;}tag.append(t);}if(tag.length()>1&&tag.charAt(tag.length()-1)=='$'){dollar=tag.toString();sql.append(tag.substring(1));state=7;}else if(tag.length()>1)input.unread(tag.substring(1).toCharArray());code=true;}
                else if(!Character.isWhitespace(c))code=true;
            }
            if(c=='\n'){line++;lineStart=true;}else if(!Character.isWhitespace(c))lineStart=false;
        }
        if(state!=0&&state!=5)throw new IOException("Comillas o comentario sin cerrar cerca de la línea "+line);
        if(!code)return null;String statement=sql.toString().trim();updateMode(statement);return new Statement(statement,start);
    }
    private void updateMode(String sql){
        var tokens=SqlScriptTools.tokens(sql);if(tokens.isEmpty()||!tokens.get(0).is("SET"))return;
        for(int i=1;i<tokens.size();i++){
            if(tokens.get(i).is("@")&&i+5<tokens.size()&&tokens.get(i+2).is("=")&&tokens.get(i+3).is("@")&&tokens.get(i+4).is("@")&&tokens.get(i+5).is("SQL_MODE"))savedModes.put(tokens.get(i+1).text().toUpperCase(java.util.Locale.ROOT),noBackslash);
            if(tokens.get(i).is("SQL_MODE")&&i+2<tokens.size()&&tokens.get(i+1).is("=")){
                var value=tokens.get(i+2);String literal=sql.substring(value.start(),value.end());
                if(literal.startsWith("'")||literal.startsWith("\""))noBackslash=literal.toUpperCase(java.util.Locale.ROOT).contains("NO_BACKSLASH_ESCAPES");
                else if(value.is("@")&&i+3<tokens.size())noBackslash=savedModes.getOrDefault(tokens.get(i+3).text().toUpperCase(java.util.Locale.ROOT),noBackslash);
            }
        }
    }
    public void close()throws IOException{input.close();}
}
