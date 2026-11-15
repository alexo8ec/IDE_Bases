package com.nexodb.util;

import com.nexodb.model.DatabaseType;

import java.util.*;

/**
 * Conservative SQL pretty printer. Only whitespace and SQL keyword case change;
 * literals, quoted identifiers and comments are emitted exactly as received.
 */
public final class SqlFormatter {
    private SqlFormatter() {}

    private static final Set<String> KEYWORDS=keywords();
    private static final Set<String> CLAUSES=Set.of("FROM","WHERE","HAVING","LIMIT","OFFSET","RETURNING","UNION","INTERSECT","EXCEPT");
    private static final Set<String> JOIN_PREFIXES=Set.of("LEFT","RIGHT","FULL","INNER","CROSS","NATURAL");
    private static final Set<String> BLOCK_ENDS=Set.of("IF","LOOP","WHILE","REPEAT","CASE");

    private static Set<String> keywords(){Set<String> words=new HashSet<>(SqlVocabulary.base());words.addAll(Set.of("UNDEFINED","INOUT"));return Collections.unmodifiableSet(words);}

    public static String format(String sql){return format(sql,DatabaseType.GENERIC);}
    public static String format(String sql,DatabaseType dialect){return checked(sql,dialect);}
    public static String formatSelection(String sql,DatabaseType dialect){return checked(sql,dialect);}

    private static String checked(String sql,DatabaseType dialect){
        if(sql==null||sql.isBlank())return sql;
        DatabaseType engine=dialect==null?DatabaseType.GENERIC:dialect;
        try{
            List<Token> before=lex(sql,engine);
            if(!balanced(before))return sql;
            String result=pretty(before).strip();
            return sameTokens(before,lex(result,engine))?result:sql;
        }catch(UnsafeSql ignored){return sql;}
    }

    private static String pretty(List<Token> tokens){
        Writer out=new Writer();Deque<Boolean> selectParens=new ArrayDeque<>();Deque<SelectFrame> selects=new ArrayDeque<>();
        int depth=0,indent=0,clauseIndent=0;
        for(int i=0;i<tokens.size();i++){
            Token token=tokens.get(i);String word=token.upper();
            if(token.type==Type.DIRECTIVE){out.newline(0);String text=token.text.strip();out.append("DELIMITER"+text.substring("DELIMITER".length()));out.newline(0);continue;}
            if(token.type==Type.LINE_COMMENT){out.append(token.text);out.newline(indent);continue;}
            if(token.type==Type.BLOCK_COMMENT){out.append(token.text);if(token.text.indexOf('\n')>=0||token.text.indexOf('\r')>=0)out.newline(indent);continue;}
            if(token.is("(")){
                boolean select=nextWord(tokens,i+1,"SELECT","WITH");if(i>0&&tokens.get(i-1).isWord("AS"))out.space();out.tight("(");depth++;selectParens.push(select);
                if(select){indent++;out.newline(indent);}continue;
            }
            if(token.is(")")){
                boolean select=!selectParens.isEmpty()&&selectParens.pop();while(!selects.isEmpty()&&selects.peek().depth>=depth)selects.pop();depth=Math.max(0,depth-1);
                if(select){indent=Math.max(0,indent-1);out.newline(indent);}out.tight(")");continue;
            }
            if(token.type==Type.WORD&&word.equals("SELECT")){
                if(!out.lineEmpty())out.newline(indent);out.append("SELECT");selects.push(new SelectFrame(depth,indent+1));clauseIndent=indent+1;out.newline(indent+1);continue;
            }
            if(token.is(",")){
                out.tight(",");SelectFrame frame=active(selects,depth);if(frame!=null&&frame.columns)out.newline(frame.itemIndent);continue;
            }
            if(token.is(";")){
                out.tight(";");out.newline(indent);while(!selects.isEmpty()&&selects.peek().depth>=depth)selects.pop();continue;
            }
            if(token.type==Type.WORD&&phrase(tokens,i,"GROUP","BY")){
                endColumns(selects,depth);out.newline(indent);out.append("GROUP BY");i++;clauseIndent=indent+1;continue;
            }
            if(token.type==Type.WORD&&phrase(tokens,i,"ORDER","BY")){
                endColumns(selects,depth);out.newline(indent);out.append("ORDER BY");i++;clauseIndent=indent+1;continue;
            }
            int join=joinLength(tokens,i);
            if(token.type==Type.WORD&&join>0){
                endColumns(selects,depth);out.newline(indent);for(int n=0;n<join;n++)out.append(tokens.get(i+n).upper());i+=join-1;clauseIndent=indent+1;continue;
            }
            if(token.type==Type.WORD&&CLAUSES.contains(word)){
                endColumns(selects,depth);out.newline(indent);out.append(word);clauseIndent=indent+1;continue;
            }
            if(token.type==Type.WORD&&word.equals("ON")){out.newline(indent+1);out.append("ON");clauseIndent=indent+2;continue;}
            if(token.type==Type.WORD&&(word.equals("AND")||word.equals("OR"))&&(depth==0||active(selects,depth)!=null)){out.newline(Math.max(indent,clauseIndent));out.append(word);continue;}
            if(token.type==Type.WORD&&word.equals("BEGIN")){
                if(!out.lineEmpty())out.newline(indent);out.append("BEGIN");indent++;out.newline(indent);continue;
            }
            if(token.type==Type.WORD&&word.equals("END")){
                indent=Math.max(0,indent-1);out.newline(indent);out.append("END");
                if(i+1<tokens.size()&&tokens.get(i+1).type==Type.WORD&&BLOCK_ENDS.contains(tokens.get(i+1).upper()))out.append(tokens.get(++i).upper());continue;
            }
            if(token.type==Type.WORD&&word.equals("THEN")){out.append("THEN");indent++;out.newline(indent);continue;}
            if(token.type==Type.WORD&&(word.equals("ELSE")||word.equals("ELSEIF"))){indent=Math.max(0,indent-1);out.newline(indent);out.append(word);indent++;if(word.equals("ELSE"))out.newline(indent);continue;}
            if(token.type==Type.WORD)out.append(KEYWORDS.contains(word)?word:token.text);
            else if(token.is(".")||token.is("@")||token.is("::"))out.tight(token.text);
            else if(token.type==Type.OPERATOR)out.operator(token.text);
            else out.append(token.text);
        }
        return out.finish();
    }

    private static SelectFrame active(Deque<SelectFrame> frames,int depth){for(SelectFrame frame:frames)if(frame.depth==depth)return frame;return null;}
    private static void endColumns(Deque<SelectFrame> frames,int depth){SelectFrame frame=active(frames,depth);if(frame!=null)frame.columns=false;}
    private static boolean phrase(List<Token> t,int at,String a,String b){return at+1<t.size()&&t.get(at).isWord(a)&&t.get(at+1).isWord(b);}
    private static boolean nextWord(List<Token> t,int at,String... words){while(at<t.size()&&(t.get(at).type==Type.LINE_COMMENT||t.get(at).type==Type.BLOCK_COMMENT))at++;if(at>=t.size())return false;for(String word:words)if(t.get(at).isWord(word))return true;return false;}
    private static int joinLength(List<Token> t,int at){
        if(at>=t.size()||t.get(at).type!=Type.WORD)return 0;String word=t.get(at).upper();if(word.equals("JOIN")||word.equals("STRAIGHT_JOIN"))return 1;if(!JOIN_PREFIXES.contains(word))return 0;
        int next=at+1;if(next<t.size()&&t.get(next).isWord("OUTER"))next++;return next<t.size()&&t.get(next).isWord("JOIN")?next-at+1:0;
    }
    private static boolean balanced(List<Token> tokens){int n=0;for(Token token:tokens){if(token.is("("))n++;else if(token.is(")")&&--n<0)return false;}return n==0;}
    private static boolean sameTokens(List<Token> a,List<Token> b){
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++){Token x=a.get(i),y=b.get(i);if(x.type!=y.type)return false;String xv=canonical(x),yv=canonical(y);if(!xv.equals(yv))return false;}return true;
    }
    private static String canonical(Token t){
        if(t.type==Type.WORD&&KEYWORDS.contains(t.upper()))return t.upper();
        if(t.type==Type.DIRECTIVE){String value=t.text.strip();return "DELIMITER"+value.substring("DELIMITER".length());}
        return t.text;
    }

    private static List<Token> lex(String sql,DatabaseType dialect){
        List<Token> result=new ArrayList<>();int i=0;boolean lineStart=true;
        while(i<sql.length()){
            char c=sql.charAt(i);
            if(Character.isWhitespace(c)){if(c=='\n'||c=='\r')lineStart=true;i++;continue;}
            if(lineStart&&regionWord(sql,i,"DELIMITER")){int end=i;while(end<sql.length()&&sql.charAt(end)!='\n'&&sql.charAt(end)!='\r')end++;result.add(new Token(Type.DIRECTIVE,sql.substring(i,end).stripTrailing()));i=end;lineStart=true;continue;}
            lineStart=false;
            if(c=='-'&&i+1<sql.length()&&sql.charAt(i+1)=='-'){int end=i+2;while(end<sql.length()&&sql.charAt(end)!='\n'&&sql.charAt(end)!='\r')end++;result.add(new Token(Type.LINE_COMMENT,sql.substring(i,end)));i=end;continue;}
            if(c=='#'&&(dialect==DatabaseType.MYSQL||dialect==DatabaseType.MARIADB||dialect==DatabaseType.GENERIC)){int end=i+1;while(end<sql.length()&&sql.charAt(end)!='\n'&&sql.charAt(end)!='\r')end++;result.add(new Token(Type.LINE_COMMENT,sql.substring(i,end)));i=end;continue;}
            if(c=='/'&&i+1<sql.length()&&sql.charAt(i+1)=='*'){int end=sql.indexOf("*/",i+2);if(end<0)throw new UnsafeSql();end+=2;result.add(new Token(Type.BLOCK_COMMENT,sql.substring(i,end)));i=end;continue;}
            if(c=='\''||c=='"'||c=='`'||c=='['){char close=c=='['?']':c;int end=quotedEnd(sql,i,close);if(end<0)throw new UnsafeSql();result.add(new Token(Type.QUOTED,sql.substring(i,end)));i=end;continue;}
            if(c=='$'){
                int tagEnd=i+1;while(tagEnd<sql.length()&&(Character.isLetterOrDigit(sql.charAt(tagEnd))||sql.charAt(tagEnd)=='_'))tagEnd++;
                if(tagEnd<sql.length()&&sql.charAt(tagEnd)=='$'){String tag=sql.substring(i,tagEnd+1);int end=sql.indexOf(tag,tagEnd+1);if(end>=0){end+=tag.length();result.add(new Token(Type.QUOTED,sql.substring(i,end)));i=end;continue;}result.add(new Token(Type.SYMBOL,tag));i=tagEnd+1;continue;}
            }
            if(isWord(c)){int end=i+1;while(end<sql.length()&&isWord(sql.charAt(end))&&!(sql.charAt(end)=='$'&&end+1<sql.length()&&sql.charAt(end+1)=='$'))end++;result.add(new Token(Type.WORD,sql.substring(i,end)));i=end;continue;}
            String op=operator(sql,i);if(op!=null){result.add(new Token(op.equals("::")?Type.SYMBOL:Type.OPERATOR,op));i+=op.length();continue;}
            Type type=",;().@".indexOf(c)>=0?Type.SYMBOL:Type.OPERATOR;result.add(new Token(type,String.valueOf(c)));i++;
        }
        return result;
    }
    private static boolean regionWord(String s,int at,String word){if(!s.regionMatches(true,at,word,0,word.length()))return false;int end=at+word.length();return end==s.length()||!isWord(s.charAt(end));}
    private static int quotedEnd(String sql,int start,char close){for(int i=start+1;i<sql.length();i++){char c=sql.charAt(i);if(c=='\\'){i++;continue;}if(c==close){if(i+1<sql.length()&&sql.charAt(i+1)==close){i++;continue;}return i+1;}}return -1;}
    private static String operator(String sql,int at){for(String op:List.of("->>","<=>","<=",">=","<>","!=",":=","||","&&","::","->","<<",">>"))if(sql.startsWith(op,at))return op;return "+-*/%=<>!|&^~:".indexOf(sql.charAt(at))>=0?String.valueOf(sql.charAt(at)):null;}
    private static boolean isWord(char c){return Character.isLetterOrDigit(c)||c=='_'||c=='$';}

    private enum Type{WORD,QUOTED,LINE_COMMENT,BLOCK_COMMENT,DIRECTIVE,SYMBOL,OPERATOR}
    private record Token(Type type,String text){String upper(){return text.toUpperCase(Locale.ROOT);}boolean is(String v){return text.equals(v);}boolean isWord(String v){return type==Type.WORD&&text.equalsIgnoreCase(v);}}
    private static final class SelectFrame{final int depth,itemIndent;boolean columns=true;SelectFrame(int d,int i){depth=d;itemIndent=i;}}
    private static final class UnsafeSql extends RuntimeException{}

    private static final class Writer{
        private final StringBuilder value=new StringBuilder();private boolean empty=true;private int indent;private String previous="";
        boolean lineEmpty(){return empty;}
        void newline(int level){trim();if(value.length()>0&&value.charAt(value.length()-1)!='\n')value.append('\n');empty=true;indent=Math.max(0,level);previous="";}
        void append(String text){if(text==null||text.isEmpty())return;start();if(!empty&&spaceNeeded(previous,text))value.append(' ');value.append(text);empty=false;previous=text;}
        void tight(String text){start();value.append(text);empty=false;previous=text;}
        void operator(String text){start();trim();if(value.length()>0&&value.charAt(value.length()-1)!='\n'&&value.charAt(value.length()-1)!=' ')value.append(' ');value.append(text).append(' ');empty=false;previous=text;}
        void space(){if(!empty&&value.length()>0&&value.charAt(value.length()-1)!=' ')value.append(' ');}
        String finish(){trim();return value.toString();}
        private void start(){if(empty){value.append("    ".repeat(indent));empty=false;}}
        private void trim(){while(value.length()>0&&(value.charAt(value.length()-1)==' '||value.charAt(value.length()-1)=='\t'))value.setLength(value.length()-1);}
        private static boolean spaceNeeded(String before,String now){if(before.isEmpty()||before.equals("(")||before.equals(".")||before.equals("@")||before.equals("::"))return false;if(now.equals(")")||now.equals(",")||now.equals(";")||now.equals(".")||now.equals("@")||now.equals("::"))return false;return !"+-*/%=<>!|&^~:".contains(before);}
    }
}
