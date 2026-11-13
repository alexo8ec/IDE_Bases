package com.nexodb.ui;

import com.nexodb.db.JdbcSession;
import java.util.*;

/** Session-only comparison choices, independent of the active SQL editor database. */
public final class ComparisonChoices {
    private final Map<String, String> sourceDatabases = new HashMap<>();
    private final Map<String, String> targetDatabases = new HashMap<>();
    private String sourceConnection = "", targetConnection = "";
    private Set<String> tables = Set.of();
    private boolean tablesConfigured, includeViews;
    static String id(JdbcSession session) {
        var profile = session.profile();
        return profile.id().isBlank() ? profile.type() + "|" + profile.host() + "|" + profile.port() + "|" + profile.username() : profile.id();
    }
    void connection(boolean source, JdbcSession session) {
        if (source) sourceConnection = id(session); else targetConnection = id(session);
    }
    String database(boolean source, JdbcSession session) { return (source ? sourceDatabases : targetDatabases).get(id(session)); }
    void database(boolean source, JdbcSession session, String database) {
        if (database != null) (source ? sourceDatabases : targetDatabases).put(id(session), database);
    }
    JdbcSession preferred(boolean source, List<JdbcSession> sessions) {
        String selected = source ? sourceConnection : targetConnection;
        return sessions.stream().filter(s -> id(s).equals(selected) && s.isOpen()).findFirst().orElse(null);
    }
    Set<String> tables() { return tables; }
    boolean tablesConfigured() { return tablesConfigured; }
    boolean includeViews() { return includeViews; }
    void tables(Set<String> selected, boolean configured, boolean views) {
        tables = Set.copyOf(selected); tablesConfigured = configured; includeViews = views;
    }
}
