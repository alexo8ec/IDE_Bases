package com.nexodb.db;

import java.io.*;
import java.util.*;

/** Client directives are parsed locally; only the validated CREATE body reaches the DDL service. */
public final class StoredProgramScript {
    public record Body(String sql,int offset){}
    private StoredProgramScript(){}
    public static String editor(SchemaItem item,String sql,boolean replacing){
        if(item.kind()==SchemaItem.Kind.VIEW)return sql;
        String delimiter="$$";while(sql.contains(delimiter))delimiter="__NEXO_"+UUID.randomUUID().toString().replace("-","")+"__";
        return "DELIMITER "+delimiter+"\n\nUSE "+StoredObjectService.quote(StoredObjectService.namespace(item))+delimiter+"\n\n"+(replacing?"DROP "+item.kind()+" IF EXISTS "+StoredObjectService.qualified(item)+delimiter+"\n\n":"")+sql.stripTrailing()+"\n"+delimiter+"\n\nDELIMITER ;\n";
    }
    public static Body body(SchemaItem item,String script)throws IOException{
        return body(item,script,"");
    }
    public static Body body(SchemaItem item,String script,String sqlMode)throws IOException{
        // A raw definition remains accepted, including internal semicolons without DELIMITER directives.
        var leading=SqlScriptTools.tokens(script);if(!leading.isEmpty()&&leading.get(0).is("CREATE")){
            // SQLyog selections sometimes include END$$ without the opening DELIMITER line.
            // Parse that separator locally too; never remove $$ inside a literal or comment.
            String tail=script;if(leading.size()>1){var directive=leading.get(leading.size()-2);if(directive.is("DELIMITER")&&leading.get(leading.size()-1).is(";")&&script.substring(directive.start(),directive.end()).equalsIgnoreCase("DELIMITER"))tail=script.substring(0,directive.start());}var endTokens=SqlScriptTools.tokens(tail);
            if(!endTokens.isEmpty()){var last=endTokens.get(endTokens.size()-1);String lastText=tail.substring(last.start(),last.end());boolean separated=lastText.equals("$$")&&endTokens.size()>1&&(endTokens.get(endTokens.size()-2).is("END")||endTokens.get(endTokens.size()-2).is(";"));
                if(lastText.equalsIgnoreCase("END$$")||separated){String prefix="DELIMITER $$\n";Body parsed=body(item,prefix+script,sqlMode);return new Body(parsed.sql(),parsed.offset()-prefix.length());}
            }
            return new Body(tail.strip(),script.indexOf(tail.strip()));
        }
        Body result=null;boolean use=false,drop=false;int cursor=0;
        try(var reader=new SqlScriptReader(new StringReader(script),sqlMode)){for(var statement=reader.next();statement!=null;statement=reader.next()){
            String sql=statement.sql();int offset=script.indexOf(sql,cursor);if(offset<0)throw new IOException("No se pudo localizar la definición dentro del script.");cursor=offset+sql.length();var tokens=SqlScriptTools.tokens(sql);
            if(tokens.isEmpty())continue;
            if(result==null&&!use&&!drop&&tokens.get(0).is("USE")&&tokens.size()==2&&tokens.get(1).text().equals(StoredObjectService.namespace(item))){use=true;continue;}
            if(result==null&&!drop&&tokens.size()>=3&&tokens.get(0).is("DROP")&&tokens.get(1).is(item.kind().name())){
                int n=2;if(tokens.size()>n+1&&tokens.get(n).is("IF")&&tokens.get(n+1).is("EXISTS"))n+=2;
                List<String> name=tokens.subList(n,tokens.size()).stream().map(SqlScriptTools.Token::text).toList();if(!name.equals(List.of(item.name()))&&!name.equals(List.of(StoredObjectService.namespace(item),".",item.name())))throw new IOException("DROP debe referirse exclusivamente al objeto abierto.");drop=true;continue;
            }
            if(result==null&&tokens.get(0).is("CREATE")){StoredObjectService.Header header=StoredObjectService.header(sql,item.kind());if(!header.name().equals(item.name()))throw new IOException("CREATE debe conservar el nombre del objeto abierto.");result=new Body(sql,offset);continue;}
            throw new IOException("El editor admite USE de la base actual, DROP del objeto abierto y una única definición CREATE. Usa Consultas para otros scripts.");
        }}
        if(result==null)throw new IOException("Falta la definición CREATE. Revisa DELIMITER y el bloque BEGIN … END.");return result;
    }
}
