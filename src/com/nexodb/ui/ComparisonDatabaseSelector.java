package com.nexodb.ui;

import com.nexodb.db.JdbcSession;
import com.nexodb.db.SchemaMetadataReader;
import com.nexodb.model.DatabaseType;
import javax.swing.*;
import java.sql.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Coalesces catalog reads and rejects obsolete asynchronous selection results. */
final class ComparisonDatabaseSelector implements AutoCloseable {
    private final ComparisonChoices memory;
    private final Runnable changed;
    private final Consumer<Throwable> failed;
    private final Map<JdbcSession, FutureTask<List<String>>> catalogs = new IdentityHashMap<>();
    private boolean closed;

    ComparisonDatabaseSelector(ComparisonChoices memory, Runnable changed, Consumer<Throwable> failed) {
        this.memory = memory; this.changed = changed; this.failed = failed;
    }
    Side side(JComboBox<String> combo, boolean source) { return new Side(combo, source); }
    @Override public void close() { closed = true; }
    final class Side {
        private final JComboBox<String> combo;
        private final boolean source;
        private JdbcSession endpoint;
        private long generation;
        private boolean applying, ready;
        Side(JComboBox<String> combo, boolean source) {
            this.combo = combo; this.source = source;
            combo.addActionListener(event -> {
                if (!applying && ready && endpoint != null) {
                    memory.database(source, endpoint, (String) combo.getSelectedItem());
                    changed.run();
                }
            });
        }
        boolean ready() { return ready && combo.getSelectedItem() != null; }
        void load(JdbcSession next) { load(next, null); }
        void load(JdbcSession next, String explicit) {
            if (closed) return;
            String previous = (String) combo.getSelectedItem();
            if (ready && endpoint != null) memory.database(source, endpoint, previous);
            endpoint = next;
            long request = ++generation;
            ready = false;
            combo.setEnabled(false);
            changed.run();
            if (next == null) { applying = true; combo.removeAllItems(); applying = false; return; }
            memory.connection(source, next);
            String remembered = memory.database(source, next);
            new SwingWorker<List<String>, Void>() {
                @Override protected List<String> doInBackground() throws Exception { return names(next); }
                @Override protected void done() {
                    if (closed || request != generation || endpoint != next) return;
                    try {
                        List<String> names = get();
                        String selected = choose(names, explicit, remembered, previous, next.profile().database());
                        applying = true;
                        try {
                            combo.setModel(new DefaultComboBoxModel<>(names.toArray(String[]::new)));
                            if (selected != null) combo.setSelectedItem(selected);
                        } finally { applying = false; }
                        ready = !names.isEmpty();
                        combo.setEnabled(ready);
                        memory.database(source, next, selected);
                        changed.run();
                    } catch (Exception error) {
                        applying = true; combo.removeAllItems(); applying = false;
                        ready = false; changed.run(); failed.accept(error);
                    }
                }
            }.execute();
        }
    }
    static String choose(List<String> available, String... preferences) {
        for (String selected : preferences) if (selected != null && available.contains(selected)) return selected;
        return available.isEmpty() ? null : available.get(0);
    }
    private List<String> names(JdbcSession endpoint) throws Exception {
        FutureTask<List<String>> task;
        synchronized (catalogs) {
            task = catalogs.computeIfAbsent(endpoint, key -> new FutureTask<>(() -> readNames(key)));
        }
        task.run();
        try { return task.get(); }
        catch (ExecutionException error) {
            synchronized (catalogs) { catalogs.remove(endpoint, task); }
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }
    private static List<String> readNames(JdbcSession endpoint) throws SQLException {
        synchronized (endpoint.connection()) {
            Set<String> names = new LinkedHashSet<>();
            var metadata = endpoint.connection().getMetaData();
            boolean schemas = endpoint.profile().type() == DatabaseType.POSTGRESQL || endpoint.profile().type() == DatabaseType.ORACLE;
            if (!schemas) try (ResultSet rows = metadata.getCatalogs()) {
                while (rows.next()) { String name = rows.getString("TABLE_CAT"); if (name != null && !name.isBlank()) names.add(name); }
            }
            if (schemas || names.isEmpty()) try (ResultSet rows = metadata.getSchemas()) {
                while (rows.next()) { String name = rows.getString("TABLE_SCHEM"); if (name != null && !name.isBlank()) names.add(name); }
            }
            return names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
    }
}
