package com.nexodb.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Source strings are Spanish; English translations live in a UTF-8 resource. */
public final class I18n {
    private static final Properties english = load();
    private static String language = resolved(AppSettings.get("language", "es"), Locale.getDefault());
    private I18n() {}

    private static Properties load() {
        Properties values = new Properties();
        try (InputStream input = I18n.class.getResourceAsStream("/com/nexodb/i18n/messages_en.properties")) {
            if (input != null) values.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException error) { throw new ExceptionInInitializerError(error); }
        return values;
    }
    public static String resolved(String preference, Locale system) {
        if ("system".equals(preference)) return "es".equals(system.getLanguage()) ? "es" : "en";
        return "en".equals(preference) ? "en" : "es";
    }
    public static Locale locale() { return Locale.forLanguageTag(language); }
    public static String tr(String text) { return "en".equals(language) ? english.getProperty(text, text) : text; }
    public static String tr(String text, Object... values) { return String.format(locale(), tr(text), values); }
    public static void initialize(String preference) {
        language = resolved(preference, Locale.getDefault());
        javax.swing.JComponent.setDefaultLocale(locale());
        javax.swing.JOptionPane.setDefaultLocale(locale());
    }
}
