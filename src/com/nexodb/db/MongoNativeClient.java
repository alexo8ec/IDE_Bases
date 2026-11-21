package com.nexodb.db;

import java.io.Closeable;import java.net.*;import java.nio.file.*;import java.util.*;

/** Reflection bridge to the official MongoDB Sync Driver jars selected by the user. */
public final class MongoNativeClient implements AutoCloseable {
 private final URLClassLoader loader;private final Object client;
 public MongoNativeClient(Path driverFolder,String uri)throws Exception{if(driverFolder==null||!Files.isDirectory(driverFolder))throw new IllegalArgumentException("Selecciona la carpeta que contiene mongodb-driver-sync, mongodb-driver-core y bson.");List<URL> urls=new ArrayList<>();try(var files=Files.list(driverFolder)){for(Path file:files.filter(p->p.getFileName().toString().endsWith(".jar")).toList())urls.add(file.toUri().toURL());}loader=new URLClassLoader(urls.toArray(URL[]::new),MongoNativeClient.class.getClassLoader());Class<?> clients=Class.forName("com.mongodb.client.MongoClients",true,loader);client=clients.getMethod("create",String.class).invoke(null,uri);}
 public List<String> databases()throws Exception{return strings(invoke(client,"listDatabaseNames"));}
 public List<String> collections(String database)throws Exception{Object db=invoke(client,"getDatabase",String.class,database);return strings(invoke(db,"listCollectionNames"));}
 public List<String> find(String database,String collection,int limit)throws Exception{Object db=invoke(client,"getDatabase",String.class,database),coll=invoke(db,"getCollection",String.class,collection),iterable=invoke(coll,"find"),limited=invoke(iterable,"limit",int.class,Math.max(1,Math.min(1000,limit)));List<String> rows=new ArrayList<>();for(Object document:iterable(limited))rows.add(Objects.toString(invoke(document,"toJson"),"{}"));return List.copyOf(rows);}
 private static List<String> strings(Object value)throws Exception{List<String> result=new ArrayList<>();for(Object item:iterable(value))result.add(Objects.toString(item,""));return List.copyOf(result);}
 private static Iterable<?> iterable(Object value)throws Exception{if(value instanceof Iterable<?> items)return items;Object iterator=invoke(value,"iterator");return ()->new Iterator<Object>(){public boolean hasNext(){try{return (Boolean)invoke(iterator,"hasNext");}catch(Exception error){throw new RuntimeException(error);}}public Object next(){try{return invoke(iterator,"next");}catch(Exception error){throw new RuntimeException(error);}}};}
 private static Object invoke(Object target,String name,Object...signatureAndValues)throws Exception{int half=signatureAndValues.length/2;Class<?>[] signature=new Class<?>[half];Object[] values=new Object[half];for(int i=0;i<half;i++){signature[i]=(Class<?>)signatureAndValues[i*2];values[i]=signatureAndValues[i*2+1];}try{return target.getClass().getMethod(name,signature).invoke(target,values);}catch(java.lang.reflect.InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception ex)throw ex;throw error;}}
 public void close(){try{invoke(client,"close");}catch(Exception ignored){}try{loader.close();}catch(Exception ignored){}}
}
