package com.nexodb.db;

public record SchemaItem(Kind kind, String catalog, String schema, String name, String detail) {
    public enum Kind {
        ROOT, SCHEMA,
        TABLES, VIEWS, PROCEDURES, FUNCTIONS, TRIGGERS, EVENTS,
        TABLE, VIEW, PROCEDURE, FUNCTION, TRIGGER, EVENT, COLUMN
    }

    public String qualifiedName() {
        StringBuilder value = new StringBuilder();
        String namespace = schema != null && !schema.isBlank() ? schema : catalog;
        if (namespace != null && !namespace.isBlank()) value.append(namespace).append('.');
        value.append(name);
        return value.toString();
    }

    @Override
    public String toString() {
        return detail == null || detail.isBlank() ? name : name + "  " + detail;
    }
}

