package com.nexodb.debug;

import com.nexodb.db.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Conservative source instrumentation; unsupported constructs are rejected before any server objects are created. */
public final class MysqlProcedure {
    public record Parameter(String name,String mode,String type){}
    public record Variable(String name,String expression,String type,String scope){}
    public record Point(int id,int line,int offset,List<Variable> variables){public Point{variables=List.copyOf(variables);}}
    public record Token(String text,String value,int start,int end,int line,boolean quoted,boolean literal){
        boolean is(String s){return !quoted&&!literal&&value.equalsIgnoreCase(s);}
        boolean name(){return !literal&&(quoted||value.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*"));}
    }
    private final String source;private final List<Parameter> parameters;private final List<Point> points;private final List<Variable> sessionVariables;private final List<Token> sourceTokens;
    private MysqlProcedure(String source,List<Parameter> parameters,List<Point> points,List<Variable> sessionVariables,List<Token> tokens){this.source=source;this.parameters=List.copyOf(parameters);this.points=List.copyOf(points);this.sessionVariables=List.copyOf(sessionVariables);this.sourceTokens=tokens;}
    public String source(){return source;}public List<Parameter> parameters(){return parameters;}public List<Point> points(){return points;}public List<Variable> sessionVariables(){return sessionVariables;}
    public Set<Integer> executableLines(){Set<Integer> result=new TreeSet<>();points.forEach(p->result.add(p.line()));return Collections.unmodifiableSet(result);}
    /** A continuation line belongs to the statement's first executable line, never to a partial SELECT. */
    public int breakpointLine(int line){if(executableLines().contains(line))return line;var tokens=sourceTokens;var token=tokens.stream().filter(t->t.line()==line).findFirst().orElse(null);if(token==null||Set.of("DECLARE","BEGIN","ELSE","ELSEIF","END","UNTIL").stream().anyMatch(token::is))return -1;var point=points.stream().filter(p->p.offset()<=token.start()).reduce((a,b)->b).orElse(null);if(point==null)return -1;for(var t:tokens)if(t.start()>=point.offset()&&t.end()<=token.start()&&t.is(";"))return -1;return point.line();}
    public static MysqlProcedure compile(StoredObjectService.Definition definition){return new Parser(definition.sql(),definition.sqlMode()).parse();}
    public String instrument(String cloneName,String pauseName){
        StringBuilder sql=new StringBuilder(source);List<Point> reversed=new ArrayList<>(points);reversed.sort(Comparator.comparingInt(Point::offset).reversed());
        for(Point p:reversed){StringBuilder context=new StringBuilder("JSON_OBJECT(");boolean first=true;
            for(Variable v:p.variables()){
                if(!first)context.append(',');first=false;String value="CAST("+v.expression()+" AS CHAR CHARACTER SET utf8mb4)";
                // Binary types use a lossless hexadecimal preview.
                if(v.type().toUpperCase(Locale.ROOT).matches(".*(?:BINARY|BLOB|\\bBIT\\b).*"))value="CONCAT('0x',HEX("+v.expression()+"))";
                context.append(literal(v.name())).append(",JSON_OBJECT('v',LEFT(").append(value).append(",4096),'n',").append(v.expression()).append(" IS NULL,'t',COALESCE(CHAR_LENGTH(").append(value).append(")>4096,0))");
            }
            context.append(')');sql.insert(p.offset(),"\nCALL "+pauseName+"("+p.id()+","+p.line()+","+context+");\n");if(sql.length()>8_000_000)throw new IllegalArgumentException("La copia instrumentada supera 8 MB.");
        }
        String result=StoredObjectService.withName(sql.toString(),SchemaItem.Kind.PROCEDURE,cloneName);
        if(result.length()>8_000_000)throw new IllegalArgumentException("La copia instrumentada supera 8 MB; reduce el procedimiento o sus variables visibles.");return result;
    }
    public static String literal(String value){if(value.isEmpty())return "''";return "CONVERT(0x"+HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8))+" USING utf8mb4)";}
    public static List<Token> lex(String source,String mode){
        boolean backslash=mode==null||!mode.contains("NO_BACKSLASH_ESCAPES"),ansi=mode!=null&&mode.contains("ANSI_QUOTES");List<Token> out=new ArrayList<>();int line=1;
        for(int i=0;i<source.length();){char c=source.charAt(i);if(Character.isWhitespace(c)){if(c=='\n')line++;i++;continue;}
            if(c=='#'||source.startsWith("--",i)&&(i+2==source.length()||Character.isWhitespace(source.charAt(i+2)))){while(i<source.length()&&source.charAt(i)!='\n')i++;continue;}
            if(source.startsWith("/*",i)){if(source.startsWith("/*!",i)||source.startsWith("/*M!",i))throw new IllegalArgumentException("La depuración no admite comentarios SQL ejecutables.");int end=source.indexOf("*/",i+2);if(end<0)throw new IllegalArgumentException("Comentario sin cerrar.");for(int p=i;p<end+2;p++)if(source.charAt(p)=='\n')line++;i=end+2;continue;}
            int start=i,startLine=line;boolean quoted=c=='`'||c=='"'&&ansi,literal=c=='\''||c=='"'&&!ansi;StringBuilder value=new StringBuilder();
            if(quoted||literal){char q=c;i++;boolean closed=false;while(i<source.length()){char x=source.charAt(i++);if(x=='\n')line++;if(x==q){if(i<source.length()&&source.charAt(i)==q){value.append(q);i++;}else{closed=true;break;}}else if(x=='\\'&&backslash&&q!='`'&&i<source.length()){char escaped=source.charAt(i++);if(escaped=='\n')line++;value.append(escaped);}else value.append(x);}if(!closed)throw new IllegalArgumentException("Comillas sin cerrar en línea "+startLine);}
            else if(Character.isLetterOrDigit(c)||c=='_'||c=='$'){while(i<source.length()&&(Character.isLetterOrDigit(source.charAt(i))||source.charAt(i)=='_'||source.charAt(i)=='$'))value.append(source.charAt(i++));}
            else{value.append(c);i++;}
            out.add(new Token(source.substring(start,i),value.toString(),start,i,startLine,quoted,literal));
        }
        return List.copyOf(out);
    }
    private static final class Parser {
        final String source;final List<Token> tokens;final List<Parameter> parameters=new ArrayList<>();final List<Point> points=new ArrayList<>();final List<Variable> userVariables=new ArrayList<>();final List<List<Variable>> scopes=new ArrayList<>();final List<Integer> scopeLines=new ArrayList<>();int pos,nesting;
        Parser(String source,String mode){this.source=source;if(source.length()>2_000_000)throw new IllegalArgumentException("El procedimiento supera 2 MB.");if(mode!=null&&mode.contains("ORACLE"))throw new IllegalArgumentException("El modo PL/SQL ORACLE de MariaDB aún no está soportado por el depurador.");tokens=lex(source,mode);}
        MysqlProcedure parse(){
            var h=StoredObjectService.header(source,SchemaItem.Kind.PROCEDURE);while(pos<tokens.size()&&tokens.get(pos).start()<h.end())pos++;expect("(");int start=pos,depth=0;
            while(pos<tokens.size()){if(at(")")&&depth==0){parameter(start,pos);pos++;break;}if(at(",")&&depth==0){parameter(start,pos);start=pos+1;}else if(at("("))depth++;else if(at(")"))depth--;pos++;}
            for(int i=pos;i<tokens.size();i++){
                Token t=tokens.get(i);boolean diagnostic=!t.literal()&&Set.of("ROW_COUNT","FOUND_ROWS").contains(t.value().toUpperCase(Locale.ROOT))&&i+1<tokens.size()&&tokens.get(i+1).is("(");
                diagnostic|=t.is("SHOW")&&i+1<tokens.size()&&(tokens.get(i+1).is("WARNINGS")||tokens.get(i+1).is("ERRORS"));
                diagnostic|=i>=2&&tokens.get(i-1).is("@")&&tokens.get(i-2).is("@")&&(t.is("WARNING_COUNT")||t.is("ERROR_COUNT"));
                if(diagnostic||Set.of("DIAGNOSTICS","PREPARE","EXECUTE","DEALLOCATE","RESIGNAL").stream().anyMatch(t::is))throw fail("No se admite "+t.text()+" en esta versión: la instrumentación podría cambiar diagnósticos o manejo de errores",t);
                if(t.is("@")&&i+1<tokens.size()&&tokens.get(i+1).name()&&(i==0||!tokens.get(i-1).is("@"))){Token name=tokens.get(++i);String key="@"+name.value();if(userVariables.stream().noneMatch(v->v.name().equalsIgnoreCase(key)))userVariables.add(new Variable(key,"@"+StoredObjectService.quote(name.value()),"sesión","sesión"));}
            }
            List<Variable> args=new ArrayList<>();for(var p:parameters)args.add(new Variable(p.name(),StoredObjectService.quote(p.name()),p.type(),p.mode()+" parámetro"));scopes.add(args);scopeLines.add(0);
            while(pos<tokens.size()&&!at("BEGIN")){if(pos+2<tokens.size()&&tokens.get(pos).name()&&tokens.get(pos+1).is(":")&&tokens.get(pos+2).is("BEGIN")){pos+=2;break;}pos++;}
            if(pos==tokens.size())throw new IllegalArgumentException("El depurador requiere un cuerpo BEGIN … END.");block();if(at(";"))pos++;if(pos!=tokens.size())throw fail("Texto adicional después del procedimiento",tokens.get(pos));
            if(points.size()>2000)throw new IllegalArgumentException("El procedimiento supera 2.000 puntos de ejecución.");return new MysqlProcedure(source,parameters,points,userVariables,tokens);
        }
        void parameter(int begin,int end){if(begin==end)return;int n=begin;String mode="IN";if(tokens.get(n).is("IN")||tokens.get(n).is("OUT")||tokens.get(n).is("INOUT"))mode=tokens.get(n++).value().toUpperCase(Locale.ROOT);if(n+1>=end||!tokens.get(n).name())throw fail("Parámetro no reconocido",tokens.get(begin));String name=tokens.get(n++).value();parameters.add(new Parameter(name,mode,source.substring(tokens.get(n).start(),tokens.get(end-1).end())));}
        void block(){if(scopes.size()>32)throw new IllegalArgumentException("Más de 32 ámbitos anidados.");int beginLine=tokens.get(pos).line();expect("BEGIN");scopes.add(new ArrayList<>());scopeLines.add(beginLine);while(at("DECLARE"))declaration();list(Set.of("END"));point();expect("END");if(pos<tokens.size()&&tokens.get(pos).name())pos++;scopes.remove(scopes.size()-1);scopeLines.remove(scopeLines.size()-1);}
        void declaration(){Token start=tokens.get(pos++);
            if(at("CONTINUE")||at("EXIT")||at("UNDO")){
                if(!at("CONTINUE"))throw fail("Solo se admite CONTINUE HANDLER FOR NOT FOUND con una acción SET",start);pos++;expect("HANDLER");expect("FOR");expect("NOT");expect("FOUND");if(!at("SET"))throw fail("El handler NOT FOUND debe tener una acción SET simple",start);simpleEnd();return;
            }
            List<Token> names=new ArrayList<>();if(pos>=tokens.size()||!tokens.get(pos).name())throw fail("DECLARE no reconocido",start);names.add(tokens.get(pos++));
            if(at("CURSOR")||at("CONDITION")){simpleEnd();return;}
            while(at(",")){pos++;if(pos>=tokens.size()||!tokens.get(pos).name())throw fail("Variable no reconocida",start);names.add(tokens.get(pos++));}
            int typeStart=pos;while(pos<tokens.size()&&!at(";")&&!at("DEFAULT"))pos++;if(typeStart==pos)throw fail("Falta tipo en DECLARE",start);String type=source.substring(tokens.get(typeStart).start(),tokens.get(pos-1).end());if(type.toUpperCase(Locale.ROOT).contains("ROW TYPE"))throw fail("No se admiten variables ROW TYPE",start);
            for(Token name:names)scopes.get(scopes.size()-1).add(new Variable(name.value(),StoredObjectService.quote(name.value()),type,"bloque línea "+scopeLines.get(scopeLines.size()-1)));simpleEnd();}
        void list(Set<String> stops){while(pos<tokens.size()&&stops.stream().noneMatch(tokens.get(pos)::is))statement();}
        void statement(){if(++nesting>64)throw new IllegalArgumentException("Más de 64 niveles de control anidados.");try{statementBody();}finally{nesting--;}}
        void statementBody(){if(at(";")){pos++;return;}Token start=tokens.get(pos);if(pos+1<tokens.size()&&tokens.get(pos).name()&&tokens.get(pos+1).is(":"))pos+=2;if(pos>=tokens.size())throw new IllegalArgumentException("Etiqueta sin instrucción.");
            if(at("BEGIN")){block();expect(";");return;}point(start);
            if(at("IF")){pos++;scanTo("THEN");expect("THEN");list(Set.of("ELSEIF","ELSE","END"));while(at("ELSEIF")){pos++;scanTo("THEN");expect("THEN");list(Set.of("ELSEIF","ELSE","END"));}if(at("ELSE")){pos++;list(Set.of("END"));}expect("END");expect("IF");expect(";");}
            else if(at("WHILE")){pos++;scanTo("DO");expect("DO");list(Set.of("END"));expect("END");expect("WHILE");endControl();}
            else if(at("LOOP")){pos++;list(Set.of("END"));expect("END");expect("LOOP");endControl();}
            else if(at("REPEAT")){pos++;list(Set.of("UNTIL"));expect("UNTIL");scanTo("END");expect("END");expect("REPEAT");endControl();}
            else if(at("CASE")){pos++;scanTo("WHEN");while(at("WHEN")){pos++;scanTo("THEN");expect("THEN");list(Set.of("WHEN","ELSE","END"));}if(at("ELSE")){pos++;list(Set.of("END"));}expect("END");expect("CASE");expect(";");}
            else simpleEnd();
        }
        void endControl(){if(pos<tokens.size()&&tokens.get(pos).name())pos++;expect(";");}
        void simpleEnd(){while(pos<tokens.size()&&!at(";"))pos++;expect(";");}
        void scanTo(String word){int depth=0,cases=0;while(pos<tokens.size()){if(depth==0&&cases==0&&at(word))return;if(at("("))depth++;else if(at(")"))depth--;else if(at("CASE"))cases++;else if(at("END")&&cases>0)cases--;pos++;}throw new IllegalArgumentException("No se encontró "+word+" en el cuerpo.");}
        void point(){point(tokens.get(pos));}
        void point(Token t){if(points.size()>=2000)throw fail("Más de 2000 puntos de ejecución",t);Map<String,Variable> visible=new LinkedHashMap<>();for(var scope:scopes)for(var v:scope)visible.put(v.name().toLowerCase(Locale.ROOT),v);for(var v:userVariables)visible.put(v.name().toLowerCase(Locale.ROOT),v);if(visible.size()>128)throw fail("Más de 128 variables visibles",t);points.add(new Point(points.size()+1,t.line(),t.start(),new ArrayList<>(visible.values())));}
        boolean at(String value){return pos<tokens.size()&&tokens.get(pos).is(value);}void expect(String value){if(!at(value))throw new IllegalArgumentException("Se esperaba "+value+" cerca de línea "+(pos<tokens.size()?tokens.get(pos).line():"final"));pos++;}
        IllegalArgumentException fail(String message,Token token){return new IllegalArgumentException(message+" (línea "+token.line()+").");}
    }
}
