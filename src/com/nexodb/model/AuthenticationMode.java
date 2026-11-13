package com.nexodb.model;

public enum AuthenticationMode {
    DATABASE("Usuario y contraseña"),
    WINDOWS("Windows integrada (GSSAPI/SSPI)");

    private final String displayName;

    AuthenticationMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() { return displayName; }

    @Override public String toString() { return displayName; }
}
