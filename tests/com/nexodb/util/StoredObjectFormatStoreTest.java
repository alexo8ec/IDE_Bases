package com.nexodb.util;

import com.nexodb.db.SchemaItem;
import com.nexodb.model.*;
import java.nio.file.*;
import java.util.*;

public final class StoredObjectFormatStoreTest {
    public static void main(String[] args)throws Exception{
        Path file=Files.createTempDirectory("stored-object-format-").resolve("formats.properties");
        var store=new StoredObjectFormatStore(file);
        var profile=new ConnectionProfile("profile-1","MariaDB",DatabaseType.MARIADB,"localhost",3306,"sales","user","","","",false);
        var item=new SchemaItem(SchemaItem.Kind.VIEW,"sales",null,"view_orders","");
        String key=StoredObjectFormatStore.key(profile,item),formatted="CREATE VIEW `view_orders` AS\nSELECT\n    id,\n    total\nFROM orders";
        equal(Optional.empty(),store.load(key,"hash-1"));store.save(key,"hash-1",formatted);
        equal(Optional.of(formatted),new StoredObjectFormatStore(file).load(key,"hash-1"));
        equal(Optional.empty(),store.load(key,"hash-changed"));
        var other=new SchemaItem(SchemaItem.Kind.VIEW,"sales",null,"view_customers","");
        if(key.equals(StoredObjectFormatStore.key(profile,other)))throw new AssertionError("Cada objeto necesita una clave aislada");
        System.out.println("StoredObjectFormatStoreTest: OK (exact multiline layout, object isolation and external-change invalidation)");
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
