package com.nexodb.util;

/** Identifier-safe SQL refactors: comments and quoted strings are never modified. */
public final class SqlRefactor {
    private SqlRefactor(){}
    public static String renameIdentifier(String sql,String oldName,String newName){
        if(sql==null||oldName==null||oldName.isBlank()||newName==null||!newName.matches("[A-Za-z_$][A-Za-z0-9_$]*"))throw new IllegalArgumentException("El identificador nuevo no es válido.");
        StringBuilder out=new StringBuilder(sql.length());for(int i=0;i<sql.length();){char c=sql.charAt(i);
            if(c=='\''||c=='"'||c=='`'){char quote=c;int start=i++;while(i<sql.length()){char x=sql.charAt(i++);if(x==quote){if(i<sql.length()&&sql.charAt(i)==quote)i++;else break;}else if(x=='\\'&&quote!='`'&&i<sql.length())i++;}out.append(sql,start,i);continue;}
            if(c=='#'||c=='-'&&i+1<sql.length()&&sql.charAt(i+1)=='-'){int start=i;i=sql.indexOf('\n',i);if(i<0)i=sql.length();out.append(sql,start,i);continue;}
            if(c=='/'&&i+1<sql.length()&&sql.charAt(i+1)=='*'){int start=i;int end=sql.indexOf("*/",i+2);i=end<0?sql.length():end+2;out.append(sql,start,i);continue;}
            if(Character.isLetterOrDigit(c)||c=='_'||c=='$'){int start=i++;while(i<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i))||sql.charAt(i)=='_'||sql.charAt(i)=='$'))i++;String token=sql.substring(start,i);out.append(token.equalsIgnoreCase(oldName)?newName:token);continue;}
            out.append(c);i++;
        }return out.toString();
    }
}
