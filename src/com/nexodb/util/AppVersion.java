package com.nexodb.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Single runtime source of truth generated from the repository VERSION file. */
public final class AppVersion {
    private static final String CURRENT=load();
    private AppVersion() {}
    public static String current(){return CURRENT;}

    private static String load(){
        try(InputStream input=AppVersion.class.getResourceAsStream("/com/nexodb/version.txt")){
            if(input==null)throw new IllegalStateException("El paquete no contiene su versión.");
            String value=new String(input.readAllBytes(),StandardCharsets.UTF_8).strip();
            if(!value.matches("[0-9]+(?:\\.[0-9]+){1,3}"))throw new IllegalStateException("La versión incorporada no es válida: "+value);
            return value;
        }catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
}
