package com.nexodb.android;

import com.nexodb.db.*;
import com.nexodb.model.*;
import com.nexodb.util.*;
import org.json.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Serialized database operations. Licensing and write confirmations are enforced on the native side. */
final class MobileBackend {
    final MainActivity activity;final MobileStore store;final MobileLicense license;final MobileDocuments documents;
    final Map<String,JdbcSession> sessions=new LinkedHashMap<>();final LinkedHashMap<String,QueryResult> results=new LinkedHashMap<>();
    private final Map<String,JdbcSession> resultOwners=new HashMap<>();
    final Map<String,MysqlTableDesign.Snapshot> designs=new HashMap<>();private final MobileTools tools;private final MobileUpdate update;
    volatile JdbcSession active;volatile SqlTransferControl transfer;private String activeId="";boolean writing;
    MobileBackend(MainActivity activity)throws Exception{this.activity=activity;store=new MobileStore(activity);license=new MobileLicense(activity,store);documents=new MobileDocuments(activity);tools=new MobileTools(this);update=new MobileUpdate(this);I18n.initialize(store.settings().optString("language","es"));}
    Object call(String method,JSONObject p)throws Exception{
        if(method.equals("bootstrap")){JSONObject out=new JSONObject().put("version",BuildConfig.VERSION_NAME).put("systemDark",(activity.getResources().getConfiguration().uiMode&48)==32).put("settings",store.settings()).put("profiles",store.publicProfiles()).put("draft",new JSONObject(store.get("draft","{}"))).put("engines",new JSONArray(List.of("MYSQL","MARIADB","POSTGRESQL")));try{out.put("license",MobileJson.value(license.current()));}catch(Exception e){out.put("license",JSONObject.NULL).put("licenseError",e.getMessage());}return out;}
        if(method.equals("settings")){String lang=p.optString("language","es"),theme=p.optString("theme","system");if(!Set.of("es","en").contains(lang)||!Set.of("light","dark","system").contains(theme))throw new IllegalArgumentException("Preferencia no válida.");store.put("settings",new JSONObject().put("language",lang).put("theme",theme).toString());I18n.initialize(lang);return store.settings();}
        if(method.equals("draft.save")){String value=p.getJSONObject("draft").toString();if(value.length()>2_000_000)throw new IllegalArgumentException("Guarda el SQL grande como archivo.");store.put("draft",value);return true;}
        if(method.equals("file.open"))return documents.open();
        if(method.equals("activate")){String token=p.optString("token");if(p.has("handle"))token=new String(java.nio.file.Files.readAllBytes(documents.file(p.getString("handle"))),StandardCharsets.UTF_8);var checked=license.verify(token);if(!activity.ask("Activar licencia",checked.customer()+"\n"+(checked.unlimited()?"Ilimitada":checked.expires())+"\nLicencia transferible. ¿Activar en este teléfono?"))throw cancelled();return license.activate(token);}
        license.current();
        return switch(method){
            case "profiles.save"->{if(!activity.ask("Guardar conexión","Se guardará el perfil en este teléfono. Las contraseñas recordadas se cifran con Android Keystore. ¿Continuar?"))throw cancelled();store.saveProfile(p.getJSONObject("profile"));yield store.publicProfiles();}
            case "profiles.delete"->{String id=p.getString("id");if(!activity.ask("Eliminar conexión",store.profile(id).optString("name")+"\nSe quitará este perfil del teléfono. ¿Continuar?"))throw cancelled();JdbcSession old=sessions.remove(id);if(old!=null)old.close();if(id.equals(activeId)){active=null;activeId="";writing=false;}store.removeProfile(id);yield store.publicProfiles();}
            case "connect"->connect(p,false);
            case "connection.test"->connect(p,true);
            case "disconnect"->{if(active!=null){if(!activity.ask("Desconectar","Se cerrará la conexión y se revertirá cualquier transacción pendiente. ¿Continuar?"))throw cancelled();sessions.remove(activeId);active.close();active=null;activeId="";writing=false;}yield true;}
            case "catalogs"->catalogs(session());
            case "database"->{selectDatabase(session(),p.getString("database"));writing=false;session().setReadOnly(true);yield SchemaMetadataReader.read(session(),p.getString("database"));}
            case "objects"->SchemaMetadataReader.read(session(),p.getString("database"));
            case "query"->query(p);
            case "write.mode"->{boolean enabled=p.optBoolean("enabled");if(enabled&&(session().profile().readOnly()||!activity.ask("Habilitar cambios","Se habilitarán instrucciones de escritura y edición de resultados. Cada escritura solicitará confirmación. ¿Continuar?")))throw cancelled();writing=enabled;session().setReadOnly(!enabled);yield writing;}
            case "transaction"->{String action=p.getString("action");authorize("Transacción",action);switch(action){case "commit"->session().commit();case "rollback"->session().rollback();case "auto"->session().setAutoCommit(p.getBoolean("value"));default->throw new IllegalArgumentException("Acción no válida.");}yield session().getAutoCommit();}
            case "rows.save"->saveRows(p);
            case "history"->new JSONArray(store.get("history","[]"));
            case "completion"->{String database=p.optString("database");List<String> tables=SchemaMetadataReader.listObjects(session(),database).stream().map(SchemaMetadataReader.DbObject::name).toList();Set<String> values=new LinkedHashSet<>(tables);values.addAll(new SqlColumnCompletion(session()).columns(p.optString("sql"),p.optString("qualifier"),database,tables));yield values;}
            case "join.completion"->new SqlJoinCompletion(session()).suggestions(p.getString("sql"),p.getInt("caret"),p.optString("database"));
            case "copy"->{activity.copy(p.getString("text"));yield true;}
            case "file.export"->{if(!activity.ask("Guardar archivo",p.optString("name","consulta.sql")+"\n¿Guardar el contenido en el archivo que selecciones?"))throw cancelled();documents.export(p.getString("text"),p.optString("name","consulta.sql"));yield true;}
            case "result.export"->{var result=result(p.getString("resultId"));String format=p.optString("format","CSV");if(!activity.ask("Exportar resultados",result.rows().size()+" filas. ¿Guardar el archivo?"))throw cancelled();java.nio.file.Path path=java.nio.file.Files.createTempFile(activity.getCacheDir().toPath(),"export-",".tmp");DataExporter.write(result,path,DataExporter.Format.valueOf(format),p.optString("table","resultado"));documents.export(path,"resultado."+format.toLowerCase(Locale.ROOT));yield true;}
            case "update.check"->update.check();
            case "update.download"->update.download();
            default->tools.call(method,p);
        };
    }
    private ConnectionProfile profile(JSONObject p){
        DatabaseType type=DatabaseType.valueOf(p.optString("type","MYSQL"));String host=p.optString("host").trim(),database=p.optString("database").trim();int port=p.optInt("port",type.defaultPort());if(host.isBlank()||port<1||port>65535||host.matches(".*[/@?].*"))throw new IllegalArgumentException("Indica host y puerto válidos.");if(type==DatabaseType.POSTGRESQL&&database.isBlank())database="postgres";
        String url=p.optString("url").trim();if(url.isBlank()){url=type.buildUrl(host,port,database);String ssl=p.optString("ssl","verify-full");url+=(url.contains("?")?"&":"?")+(type==DatabaseType.POSTGRESQL?"sslmode="+(ssl.equals("disabled")?"disable":ssl.equals("required")?"require":"verify-full"):"sslMode="+(ssl.equals("disabled")?"disable":ssl.equals("required")?"trust":"verify-full"));}
        return new ConnectionProfile(p.optString("id"),p.optString("name",host),type,host,port,database,p.optString("username"),url,type.driverClass(),"",p.optBoolean("readOnly",true));
    }
    private Object connect(JSONObject p,boolean test)throws Exception{
        JSONObject data=p.has("profile")?p.getJSONObject("profile"):store.profile(p.getString("id"));var profile=profile(data);char[] password=store.password(data,p.optString("password",data.optString("password")));JdbcSession opened;
        try{activity.progress("Conectando…");opened=JdbcSession.open(profile,password);}finally{Arrays.fill(password,'\0');}
        try{JSONArray bases=catalogs(opened);if(test)return new JSONObject().put("message","Conexión correcta").put("catalogs",bases);JdbcSession old=sessions.put(profile.id(),opened);if(old!=null)old.close();active=opened;activeId=profile.id();writing=false;opened.setReadOnly(true);String database=profile.type()==DatabaseType.POSTGRESQL?JdbcCompat.schema(opened.connection()):opened.connection().getCatalog();return new JSONObject().put("id",activeId).put("database",Objects.toString(database,profile.database())).put("catalogs",bases).put("protected",profile.readOnly());}finally{if(test)opened.close();}
    }
    JdbcSession session(){if(active==null||!active.isOpen())throw new IllegalStateException("Conecta una base de datos primero.");return active;}
    JSONArray catalogs(JdbcSession s)throws Exception{JSONArray result=new JSONArray();boolean pg=s.profile().type()==DatabaseType.POSTGRESQL;try(ResultSet r=pg?s.connection().getMetaData().getSchemas():s.connection().getMetaData().getCatalogs()){while(r.next())result.put(r.getString(pg?"TABLE_SCHEM":"TABLE_CAT"));}return result;}
    void selectDatabase(JdbcSession s,String database)throws Exception{if(s.profile().type()==DatabaseType.POSTGRESQL){try(Statement statement=s.connection().createStatement()){statement.execute("SET search_path TO "+VisualQuery.quote(database,"\""));}}else s.connection().setCatalog(database);}
    private Object query(JSONObject p)throws Exception{
        String sql=p.getString("sql");if(sql.isBlank())throw new IllegalArgumentException("Escribe una consulta SQL.");List<String> statements=new ArrayList<>();try(SqlScriptReader reader=new SqlScriptReader(new java.io.StringReader(sql))){SqlScriptReader.Statement statement;while((statement=reader.next())!=null){statements.add(statement.sql());if(statements.size()>16)throw new IllegalArgumentException("Usa Importar SQL para ejecutar archivos de más de 16 sentencias.");}}
        boolean writes=statements.stream().anyMatch(statement->!SqlExecutionPolicy.readOnly(statement));if(writes)authorize("Ejecutar SQL",sql);else if(!writing&&!session().getReadOnly())session().setReadOnly(true);
        JSONArray values=new JSONArray();for(String statement:statements)values.put(queryOne(p,statement));if(values.length()==0)throw new IllegalArgumentException("No hay una sentencia SQL para ejecutar.");if(values.length()==1)return values.get(0);return new JSONObject().put("results",values);
    }
    private Object queryOne(JSONObject p,String sql)throws Exception{
        int limit=p.optBoolean("unlimited")?Integer.MAX_VALUE:Math.max(1,p.optInt("limit",1000));QueryResult result=session().execute(sql,limit);String id=UUID.randomUUID().toString();results.put(id,result);resultOwners.put(id,session());while(results.size()>16){String expired=results.keySet().iterator().next();results.remove(expired);resultOwners.remove(expired);}
        JSONArray previous=new JSONArray(store.get("history","[]")),history=new JSONArray();history.put(new JSONObject().put("sql",sql).put("profile",activeId).put("time",Instant.now().toString()).put("elapsed",result.elapsedMillis()));for(int i=0;i<Math.min(499,previous.length());i++)history.put(previous.get(i));store.put("history",history.toString());
        JSONObject output=MobileJson.object(result);JSONArray rows=new JSONArray();for(var row:result.rows()){JSONArray values=new JSONArray();for(Object item:row)values.put(item==null?JSONObject.NULL:item.toString());rows.put(values);}output.put("rows",rows).put("resultId",id);return output;
    }
    QueryResult result(String id){var result=results.get(id);if(result==null)throw new IllegalArgumentException("Ejecuta otra vez la consulta para recuperar el resultado.");return result;}
    void authorize(String title,String sql)throws Exception{if(session().profile().readOnly()||!writing)throw new IllegalStateException("La conexión está en Solo lectura. Activa Habilitar cambios para continuar.");if(!activity.ask(title,session().profile().name()+"\n\n"+sql+"\n\n¿Aplicar este cambio en la base de datos?"))throw cancelled();session().setReadOnly(false);}
    private Object saveRows(JSONObject p)throws Exception{
        var result=result(p.getString("resultId"));if(resultOwners.get(p.getString("resultId"))!=session())throw new IllegalArgumentException("El resultado pertenece a otra conexión. Ejecuta de nuevo la consulta antes de editar.");if(result.editing()==null||result.editing().target()==null)throw new IllegalArgumentException("Este resultado es de solo lectura.");JSONArray incoming=p.getJSONArray("changes");List<QueryEditSupport.RowChange> changes=new ArrayList<>();
        for(int i=0;i<incoming.length();i++){JSONObject change=incoming.getJSONObject(i);int row=change.getInt("row");var kind=QueryEditSupport.ChangeKind.valueOf(change.optString("kind","UPDATE"));List<Object> original=kind==QueryEditSupport.ChangeKind.INSERT?new ArrayList<>(Collections.nCopies(result.columns().size(),null)):result.rows().get(row);Map<Integer,Object> values=new LinkedHashMap<>();JSONObject cells=change.optJSONObject("values");if(cells!=null)for(Iterator<String> keys=cells.keys();keys.hasNext();){String key=keys.next();Object value=MobileJson.nullable(cells,key);if(value instanceof JSONObject flag&&flag.optBoolean("defaultValue"))value=QueryEditSupport.DefaultValue.INSTANCE;values.put(Integer.parseInt(key),value);}changes.add(new QueryEditSupport.RowChange(row,original,values,kind));}
        authorize("Guardar cambios",changes.size()+" filas en "+result.editing().target().qualifiedTable());return QueryEditSupport.saveChanges(session().connection(),result.editing().target(),changes);
    }
    void cancel(){JdbcSession s=active;SqlTransferControl current=transfer;new Thread(()->{try{if(current!=null)current.cancel();if(s!=null)s.cancel();}catch(Exception ignored){}},"nexodb-cancel").start();}
    void close(){for(JdbcSession s:sessions.values())try{s.close();}catch(Exception ignored){}sessions.clear();documents.close();}
    static java.util.concurrent.CancellationException cancelled(){return new java.util.concurrent.CancellationException("Operación cancelada.");}
}
