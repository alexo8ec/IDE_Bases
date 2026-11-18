package com.nexodb.update;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;
import java.util.jar.JarFile;

/** The distributable JAR must identify itself with exactly the release version. */
public final class PackagedVersionTest {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Expected packaged JAR path");
        String expected=Files.readString(Path.of("VERSION"),StandardCharsets.UTF_8).strip();
        try(JarFile jar=new JarFile(args[0])){
            var entry=jar.getJarEntry("com/nexodb/version.txt");
            if(entry==null)throw new AssertionError("Packaged version resource is missing");
            String embedded=new String(jar.getInputStream(entry).readAllBytes(),StandardCharsets.UTF_8).strip();
            equal(expected,embedded);
            equal(expected,jar.getManifest().getMainAttributes().getValue("Implementation-Version"));
        }
        equal(expected,UpdateService.CURRENT_VERSION);
        System.out.println("PackagedVersionTest: OK (VERSION, runtime resource and JAR manifest agree on "+expected+")");
    }
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError(expected+" != "+actual);}
}
