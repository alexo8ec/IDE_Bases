package com.nexodb.db;

import java.util.*;

/** Token positions refer to the original script. Strings and ordinary comments are never rewritten. */
final class SqlScriptTools {
    private static final Set<String> TABLE_CONTEXT=Set.of("FROM","JOIN","INTO","UPDATE","TABLE","VIEW","REFERENCES","PROCEDURE","FUNCTION","TRIGGER","EVENT","TRUNCATE");
    record Token(String text,int start,int end,boolean name) {boolean is(String word){return text.equalsIgnoreCase(word);}}
    private SqlScriptTools() {}
    static List<Token> tokens(String sql){
        List<Token> result=new ArrayList<>();boolean executable=false;
        for(int i=0;i<sql.length();){
            char c=sql.charAt(i);int start=i;
            if(Character.isWhitespace(c)||c=='\uFEFF'){i++;continue;}
            if(executable&&sql.startsWith("*/",i)){executable=false;i+=2;continue;}
            if(c=='#'||sql.startsWith("--",i)&&(i+2==sql.length()||Character.isWhitespace(sql.charAt(i+2)))){int end=sql.indexOf('\n',i);i=end<0?sql.length():end+1;continue;}
            if(sql.startsWith("/*",i)){
                int marker=sql.startsWith("/*!",i)?3:sql.startsWith("/*M!",i)?4:0;
                if(marker>0){i+=marker;while(i<sql.length()&&Character.isDigit(sql.charAt(i)))i++;executable=true;continue;}
                int end=sql.indexOf("*/",i+2);i=end<0?sql.length():end+2;continue;
            }
            if(c=='\''||c=='"'||c=='`'){
                char quote=c;StringBuilder value=quote=='\''?null:new StringBuilder();i++;
                while(i<sql.length()){c=sql.charAt(i++);if(c==quote){if(i<sql.length()&&sql.charAt(i)==quote){if(value!=null)value.append(quote);i++;}else break;}else if(c=='\\'&&quote!='`'&&i<sql.length()){char escaped=sql.charAt(i++);if(value!=null)value.append(escaped);}else if(value!=null)value.append(c);}
                // Double quotes are identifiers under ANSI_QUOTES. A following dot disambiguates
                // a qualified name, so remapping also works for such dumps without touching literals.
                result.add(new Token(value==null?"<string>":value.toString(),start,i,value!=null));continue;
            }
            if(Character.isLetterOrDigit(c)||c=='_'||c=='$'){
                i++;while(i<sql.length()&&(Character.isLetterOrDigit(sql.charAt(i))||sql.charAt(i)=='_'||sql.charAt(i)=='$'))i++;
                result.add(new Token(sql.substring(start,i),start,i,true));continue;
            }
            result.add(new Token(String.valueOf(c),i,i+1,false));i++;
        }
        return result;
    }
    static String databaseDirective(String sql){
        List<Token> tokens=tokens(sql);if(tokens.size()>1&&tokens.get(0).is("USE")&&tokens.get(1).name())return tokens.get(1).text();
        if(tokens.size()>2&&tokens.get(0).is("CREATE")&&(tokens.get(1).is("DATABASE")||tokens.get(1).is("SCHEMA"))){int index=2;if(tokens.get(index).is("IF"))index+=3;return index<tokens.size()&&tokens.get(index).name()?tokens.get(index).text():null;}
        return null;
    }
    static String remap(String sql,Set<String> sources,String target){
        List<Token> tokens=tokens(sql);StringBuilder result=new StringBuilder(sql);
        for(int i=tokens.size()-2;i>=0;i--){Token token=tokens.get(i);if(token.name()&&sources.contains(token.text())&&tokens.get(i+1).is("."))result.replace(token.start(),token.end(),SqlBackupService.quote(target));}
        return result.toString();
    }
    static String withoutDefiner(String ddl){
        List<Token> tokens=tokens(ddl);
        for(int i=0;i<tokens.size();i++){
            Token token=tokens.get(i);
            if(i>0&&(token.is("VIEW")||token.is("PROCEDURE")||token.is("FUNCTION")||token.is("TRIGGER")||token.is("EVENT")))break;
            if(token.is("DEFINER")&&i+4<tokens.size()&&tokens.get(i+1).is("=")&&tokens.get(i+3).is("@"))return ddl.substring(0,token.start())+ddl.substring(tokens.get(i+4).end()).stripLeading();
        }
        return ddl;
    }
    static boolean destructive(String sql){List<Token> t=tokens(sql);return !t.isEmpty()&&Set.of("DROP","TRUNCATE","DELETE","UPDATE","REPLACE","ALTER").contains(t.get(0).text().toUpperCase(Locale.ROOT));}
    static Set<String> referencedDatabases(String sql){
        Set<String> databases=new LinkedHashSet<>();List<Token> tokens=tokens(sql);
        for(int i=0;i<tokens.size()-3;i++)if(TABLE_CONTEXT.contains(tokens.get(i).text().toUpperCase(Locale.ROOT))){
            int next=i+1;if(tokens.get(next).is("IF"))next+=tokens.get(next+1).is("NOT")?3:2;
            if(next+2<tokens.size()&&tokens.get(next).name()&&tokens.get(next+1).is(".")&&tokens.get(next+2).name())databases.add(tokens.get(next).text());
        }
        return databases;
    }
}
