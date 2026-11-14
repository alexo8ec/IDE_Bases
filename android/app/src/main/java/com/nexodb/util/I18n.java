package com.nexodb.util;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.io.*;

public final class I18n {
    private static String language="es";private static final Properties english=new Properties();
    static {try(var input=I18n.class.getResourceAsStream("/com/nexodb/i18n/messages_en.properties")){if(input!=null)english.load(new InputStreamReader(input,StandardCharsets.UTF_8));}catch(IOException ignored){}}
    private I18n(){}
    public static void initialize(String value){language="en".equals(value)?"en":"es";}
    public static String tr(String value){return language.equals("en")?english.getProperty(value,value):value;}
    public static String tr(String value,Object...args){return String.format(Locale.forLanguageTag(language),tr(value),args);}
}
