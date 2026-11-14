package com.nexodb.db;

import java.sql.SQLException;
import java.util.*;
import java.util.regex.*;

/** Deterministic explanations retain the engine's message and distinguish exact positions from fallbacks. */
public record SqlDiagnostic(int line,int column,boolean precise,String explanation,String suggestion,String original,int code,String sqlState) {
    public static SqlDiagnostic analyze(Throwable error,String submitted,String document,int baseOffset){return analyze(error,submitted,document,baseOffset,0);}
    public static SqlDiagnostic analyze(Throwable error,String submitted,String document,int baseOffset,int fallbackLine){
        Throwable root=error;Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());SQLException sql=null;while(root!=null&&seen.add(root)){if(root instanceof SQLException e&&(sql==null||e.getErrorCode()!=0))sql=e;if(root.getCause()==null)break;root=root.getCause();}
        String original=Objects.toString(error.getMessage(),error.toString());if(root!=null&&root!=error&&!original.contains(Objects.toString(root.getMessage(),"")))original+="\n"+root.getMessage();String text=original.toLowerCase(Locale.ROOT);int code=sql==null?0:sql.getErrorCode();String state=sql==null?"":Objects.toString(sql.getSQLState(),"");
        int localLine=0,column=1,position=0;Matcher linePattern=Pattern.compile("(?iu)\\b(?:at\\s+line|line|línea|linea)\\s*:?\\s*(\\d+)").matcher(original);while(linePattern.find())localLine=number(linePattern.group(1));Matcher posPattern=Pattern.compile("(?i)\\b(?:position|posición)\\s*:\\s*(\\d+)").matcher(original);if(posPattern.find())position=number(posPattern.group(1));
        // PostgreSQL exposes structured positions even when server messages are localized.
        if(sql!=null)try{Object server=sql.getClass().getMethod("getServerErrorMessage").invoke(sql);if(server!=null)position=((Number)server.getClass().getMethod("getPosition").invoke(server)).intValue();}catch(ReflectiveOperationException ignored){}
        boolean precise=(localLine>0&&localLine<=1+submitted.chars().filter(c->c=='\n').count())||position>0;int localOffset=0;
        if(position>0){int characters=Math.min(position-1,submitted.codePointCount(0,submitted.length()));localOffset=submitted.offsetByCodePoints(0,characters);}else if(localLine>0){for(int i=1;i<localLine;i++){int end=submitted.indexOf('\n',localOffset);if(end<0){localOffset=submitted.length();break;}localOffset=end+1;}}
        else if(fallbackLine>0){localOffset=offsetAtLine(submitted,fallbackLine);}
        int at=Math.max(0,Math.min(document.length(),baseOffset+localOffset));int line=1;for(int i=0;i<at;i++)if(document.charAt(i)=='\n')line++;column=at-document.lastIndexOf('\n',at-1);
        String explanation,suggestion;
        if(code==1064||state.equals("42601")||text.contains("syntax error")||text.contains("sql syntax")){explanation="El motor no pudo interpretar la sintaxis SQL.";suggestion=text.contains("near ''")||localLine>submitted.lines().count()?"El error se detectó al llegar al final. Revisa cierres END / END IF / END LOOP, comillas, paréntesis y puntos y coma; la omisión puede estar antes de la línea marcada.":"Revisa la palabra o símbolo indicado por el motor, las comas, comillas, paréntesis y los puntos y coma de esta instrucción. En el editor de SP, DELIMITER debe cerrar el bloque completo.";}
        else if(code==1054||code==207||state.equals("42703")||text.contains("unknown column")){explanation="La consulta usa una columna o variable que el motor no reconoce.";suggestion="Verifica el nombre, el alias de la tabla y el ámbito de la variable. Las variables locales del SP deben declararse antes de las instrucciones de su bloque.";}
        else if(code==1146||code==208||state.equals("42P01")){explanation="No se encontró la tabla o vista indicada.";suggestion="Comprueba la base y el esquema activos, el nombre del objeto y las diferencias de mayúsculas/minúsculas del servidor.";}
        else if(code==1305||code==1370){explanation="La rutina no existe o no tienes permiso para ejecutarla.";suggestion="Comprueba su nombre completo, la base activa y los permisos EXECUTE del usuario.";}
        else if(code==1062||state.equals("23505")){explanation="El valor duplica una clave única existente.";suggestion="Comprueba la clave primaria o el índice UNIQUE y los valores que se intentan insertar o actualizar.";}
        else if(code==1048||code==1364||state.equals("23502")){explanation="Falta un valor obligatorio o se intentó guardar NULL.";suggestion="Asigna un valor a las columnas NOT NULL y revisa los parámetros y variables usados en la escritura.";}
        else if(code==1451||code==1452||state.equals("23503")){explanation="La operación incumple una relación de clave foránea.";suggestion="Revisa el registro relacionado antes de insertar, actualizar o eliminar.";}
        else if(code==1406||code==1264||state.startsWith("22")){explanation="Un valor no se puede convertir o no cabe en el tipo de destino.";suggestion="Comprueba longitudes, precisión decimal, fechas y conversiones de los valores y variables.";}
        else if(code==1044||code==1045||code==1142||state.equals("42501")){explanation="El servidor rechazó la operación por permisos o autenticación.";suggestion="Revisa los permisos del usuario conectado para la base y la operación indicada.";}
        else if(code==1213||code==1205||state.equals("40P01")){explanation="La operación se detuvo por un bloqueo o interbloqueo.";suggestion="Revisa las transacciones abiertas y el orden de acceso a las tablas. Confirma el estado de la transacción antes de repetir una escritura.";}
        else if(state.startsWith("08")){explanation="Se perdió o no se pudo establecer la conexión con el servidor.";suggestion="Comprueba la conexión. Verifica si una escritura llegó a aplicarse antes de volver a ejecutarla.";}
        else if(state.equals("45000")){explanation="El procedimiento lanzó un error explícito mediante SIGNAL.";suggestion="Revisa la condición de SIGNAL y los valores de las variables en la última instrucción registrada.";}
        else{explanation="El servidor o el cliente rechazó esta operación.";suggestion="Revisa el detalle original y la instrucción marcada. El mensaje original conserva la causa específica.";}
        return new SqlDiagnostic(line,column,precise,explanation,suggestion,original,code,state);
    }
    private static int number(String value){try{return Integer.parseInt(value);}catch(NumberFormatException ignored){return 0;}}
    public static int offsetAtLine(String sql,int line){int at=0;for(int i=1;i<line;i++){int n=sql.indexOf('\n',at);if(n<0)return sql.length();at=n+1;}return at;}
    public String display(){return (precise?"Línea ":"Instrucción asociada · línea ")+line+", columna "+column+(precise?"":" (el motor no informó una posición exacta)")+"\n\n"+explanation+"\n"+suggestion+"\n\nDetalle original"+(code!=0?" · código "+code:"")+(!sqlState.isBlank()?" · SQLSTATE "+sqlState:"")+":\n"+original;}
}
