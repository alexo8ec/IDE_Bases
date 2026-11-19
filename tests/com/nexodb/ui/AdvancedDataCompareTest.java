package com.nexodb.ui;

import com.nexodb.db.JdbcSession;
import com.nexodb.model.ConnectionProfile;
import com.nexodb.model.DatabaseType;
import javax.swing.*;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Real cross-schema comparison with renamed columns and a user-selected key. */
public final class AdvancedDataCompareTest {
    public static void main(String[] args)throws Exception{
        Path driver=Path.of(System.getProperty("nexodb.test.h2Jar","build/h2-test.jar")).toAbsolutePath();
        ConnectionProfile leftProfile=new ConnectionProfile("compare-left","Origen",DatabaseType.H2,"",0,"mem:compare_left","sa","jdbc:h2:mem:compare_left;MODE=MySQL;DB_CLOSE_DELAY=-1","org.h2.Driver",driver.toString(),false);
        ConnectionProfile rightProfile=new ConnectionProfile("compare-right","Destino",DatabaseType.H2,"",0,"mem:compare_right","sa","jdbc:h2:mem:compare_right;MODE=MySQL;DB_CLOSE_DELAY=-1","org.h2.Driver",driver.toString(),false);
        try(JdbcSession left=JdbcSession.open(leftProfile,new char[0]);JdbcSession right=JdbcSession.open(rightProfile,new char[0])){
            try(Statement sql=left.connection().createStatement()){sql.execute("CREATE TABLE items(id INT PRIMARY KEY,legacy_name VARCHAR(80),qty DECIMAL(10,2))");sql.execute("INSERT INTO items VALUES(1,'Alpha',2.00),(2,'Beta',3.00)");}
            try(Statement sql=right.connection().createStatement()){sql.execute("CREATE TABLE items(code INT PRIMARY KEY,name VARCHAR(80),amount DECIMAL(10,2))");sql.execute("INSERT INTO items VALUES(1,'Old',2.00),(3,'Extra',8.00)");}
            try(Statement sql=left.connection().createStatement()){sql.execute("CREATE TABLE bulk(id INT PRIMARY KEY,txt VARCHAR(40))");sql.execute("INSERT INTO bulk SELECT X, CONCAT('v',X) FROM SYSTEM_RANGE(1,25005)");}
            try(Statement sql=right.connection().createStatement()){sql.execute("CREATE TABLE bulk(id INT PRIMARY KEY,txt VARCHAR(40))");sql.execute("INSERT INTO bulk SELECT X, CONCAT('v',X) FROM SYSTEM_RANGE(1,25005)");}
            DataCompareDialog[] holder=new DataCompareDialog[1];SwingUtilities.invokeAndWait(()->holder[0]=new DataCompareDialog(null,left,List.of(left,right),(session,text)->{}));
            try{
                var mapping=new DataCompareDialog.TableMapping("ITEMS","ITEMS",List.of(
                        new DataCompareDialog.ColumnMapping("ID","CODE",true),
                        new DataCompareDialog.ColumnMapping("LEGACY_NAME","NAME",false),
                        new DataCompareDialog.ColumnMapping("QTY","AMOUNT",false)));
                var options=new DataCompareDialog.Options(false,false,false,false,false,100,"",Set.of("items"),true,Map.of("items",mapping));
                var result=holder[0].buildComparison(left,"PUBLIC",right,"PUBLIC",options,value->{});
                equal(1,result.tables().size());
                var table=result.tables().stream().filter(value->value.table().equalsIgnoreCase("items")).findFirst().orElseThrow();
                equal(1,table.missing());equal(1,table.extra());equal(1,table.changed());
                require(result.script().contains("\"CODE\""),"Mapped target key missing from script");
                require(result.script().contains("\"NAME\""),"Mapped target value missing from script");
                require(result.script().contains("WHERE \"CODE\"=1"),"Mapped key not used by UPDATE");
                require(result.script().contains("'Beta'"),"Missing source row not included in INSERT");
                var bulkOptions=new DataCompareDialog.Options(false,false,false,false,false,30000,"",Set.of("bulk"),true,Map.of());var bulk=holder[0].buildComparison(left,"PUBLIC",right,"PUBLIC",bulkOptions,value->{}).tables().stream().filter(value->value.table().equalsIgnoreCase("bulk")).findFirst().orElseThrow();equal(25005,bulk.sourceRows());equal(25005,bulk.targetRows());equal(0,bulk.changed());equal("Igual",bulk.status());
            }finally{SwingUtilities.invokeAndWait(holder[0]::dispose);}
        }
        System.out.println("AdvancedDataCompareTest: OK (renamed columns, custom key and streaming merge over 25,005 rows)");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void equal(Object expected,Object actual){if(!java.util.Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
