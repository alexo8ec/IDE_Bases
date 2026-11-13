package com.nexodb.db;

import com.nexodb.MetadataFixture;
import com.nexodb.model.DatabaseType;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static com.nexodb.MetadataFixture.equal;

public final class SchemaMetadataReaderTest {
    static final class Database implements InvocationHandler {
        int tables,columns,closed,size=100;
        boolean tableOpen;
        String catalog,schema,pattern;
        CountDownLatch parallel;
        public Object invoke(Object proxy,Method method,Object[] args)throws Exception {
            if(method.getName().equals("getTables")){
                tables++; catalog=(String)args[0]; schema=(String)args[1];
                if(parallel!=null){parallel.countDown();if(!parallel.await(5,TimeUnit.SECONDS))throw new AssertionError("Independent connections were read sequentially");}
                if(tableOpen)throw new AssertionError("Overlapping metadata reads"); tableOpen=true;
                List<Object[]> data=new ArrayList<>();for(int i=0;i<250;i++) data.add(new Object[]{"t"+i,"TABLE"});
                data.add(new Object[]{"t_%","TABLE"});data.add(new Object[]{"v_data","VIEW"});
                return MetadataFixture.track(MetadataFixture.rows(new String[]{"TABLE_NAME","TABLE_TYPE"},data.toArray(Object[][]::new)),()->{tableOpen=false;closed++;});
            }
            if(method.getName().equals("getColumns")){
                columns++;if(tableOpen)throw new AssertionError("Table result set must be closed before column read");
                pattern=(String)args[2];List<Object[]> data=new ArrayList<>();
                for(int i=0;i<251;i++){
                    String table=i==250?"t_%":"t"+i;
                    if(!pattern.equals("%")&&!pattern.replace("\\_","_").replace("\\%","%").equals(table))continue;
                    data.add(new Object[]{table,"name","VARCHAR",size,0,1,null,"NO",2});
                    data.add(new Object[]{table,"id","INT",10,0,0,null,"YES",1});
                }
                return MetadataFixture.track(MetadataFixture.rows(new String[]{"TABLE_NAME","COLUMN_NAME","TYPE_NAME","COLUMN_SIZE","DECIMAL_DIGITS","NULLABLE","COLUMN_DEF","IS_AUTOINCREMENT","ORDINAL_POSITION"},data.toArray(Object[][]::new)),()->closed++);
            }
            throw new AssertionError(method.getName());
        }
    }
    public static void main(String[] args)throws Exception {
        Database database=new Database();JdbcSession session=MetadataFixture.session("local",DatabaseType.MYSQL,database);
        var snapshot=SchemaMetadataReader.read(session,"tenant_%");
        equal(1,database.tables);equal(1,database.columns);equal(2,database.closed);equal("tenant_%",database.catalog);equal(null,database.schema);
        equal(252,snapshot.objects().size());var table=snapshot.objects().get("table:t0");
        equal(List.of("id","name"),new ArrayList<>(table.columns().keySet()));
        equal("INT NOT NULL AUTO_INCREMENT",table.columns().get("id").sqlDefinition());
        database.size=200;var fresh=SchemaMetadataReader.read(session,"tenant_%");
        equal(200,fresh.objects().get("table:t0").columns().get("name").size());equal(100,table.columns().get("name").size());
        var selected=SchemaMetadataReader.read(session,"tenant_%",Set.of("table:t_%"));
        equal(Set.of("table:t_%"),selected.objects().keySet());equal("t\\_\\%",database.pattern);
        int reads=database.columns;SchemaMetadataReader.read(session,"tenant_%",Set.of("view:v_data"));equal(reads,database.columns);
        equal(0,SchemaMetadataReader.read(session,"tenant_%",Set.of("table:missing")).objects().size());equal(reads,database.columns);
        Database postgres=new Database();SchemaMetadataReader.read(MetadataFixture.session("pg",DatabaseType.POSTGRESQL,postgres),"schema_%");
        equal(null,postgres.catalog);equal("schema\\_\\%",postgres.schema);
        Database a=new Database(),b=new Database();a.parallel=b.parallel=new CountDownLatch(2);
        var pair=SchemaMetadataReader.readPair(MetadataFixture.session("a",DatabaseType.MYSQL,a),"db1",MetadataFixture.session("b",DatabaseType.MYSQL,b),"db2",Set.of("table:t2"));
        equal(Set.of("table:t2"),pair.source().objects().keySet());equal(Set.of("table:t2"),pair.target().objects().keySet());
        Database serial=new Database();JdbcSession shared=MetadataFixture.session("shared",DatabaseType.MYSQL,serial);
        SchemaMetadataReader.readPair(shared,"db1",shared,"db2");equal(2,serial.tables);equal(2,serial.columns);
        System.out.println("SchemaMetadataReaderTest: OK (251 tables in 2 metadata reads; selection and parallel snapshots verified)");
    }
}
