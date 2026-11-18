package com.nexodb.util;

import com.nexodb.model.ConnectionProfile;
import com.nexodb.model.DatabaseType;
import com.nexodb.model.AuthenticationMode;
import com.nexodb.model.SshConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public final class ProfileStore {
    private Preferences root() {
        return Preferences.userRoot().node("com/nexodb/studio/profiles");
    }

    public List<ConnectionProfile> load() {
        List<ConnectionProfile> profiles = new ArrayList<>();
        try {
            Preferences root = root();
            for (String id : root.childrenNames()) {
                Preferences node = root.node(id);
                try {
                    profiles.add(new ConnectionProfile(
                            id,
                            node.get("name", "Conexión"),
                            DatabaseType.valueOf(node.get("type", DatabaseType.GENERIC.name())),
                            node.get("host", ""),
                            node.getInt("port", 0),
                            node.get("database", ""),
                            node.get("username", ""),
                            node.get("jdbcUrl", ""),
                            node.get("driverClass", ""),
                            node.get("driverJar", ""),
                            AuthenticationMode.valueOf(node.get("authenticationMode", AuthenticationMode.DATABASE.name())),
                            new SshConfig(node.getBoolean("sshEnabled",false),node.get("sshHost",""),node.getInt("sshPort",22),node.get("sshUsername",""),
                                    SshConfig.Authentication.valueOf(node.get("sshAuthentication",SshConfig.Authentication.PASSWORD.name())),node.get("sshPrivateKey",""),node.get("sshKnownHosts",""),
                                    node.getBoolean("sshStrictHostKeyChecking",false),node.getInt("sshLocalPort",0),node.getInt("sshConnectTimeout",10),node.getInt("sshKeepAlive",30)),
                            node.getBoolean("readOnly", false)
                    ));
                } catch (RuntimeException ignored) {
                }
            }
        } catch (BackingStoreException | SecurityException ignored) {
        }
        profiles.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return profiles;
    }

    public ConnectionProfile save(ConnectionProfile profile) {
        Preferences root = root();
        String id = profile.id().isBlank() ? UUID.randomUUID().toString() : profile.id();
        ConnectionProfile saved = new ConnectionProfile(id, profile.name(), profile.type(), profile.host(), profile.port(),
                profile.database(), profile.username(), profile.jdbcUrl(), profile.driverClass(), profile.driverJar(),
                profile.authenticationMode(), profile.ssh(), profile.readOnly());
        Preferences node = root.node(id);
        node.put("name", saved.name());
        node.put("type", saved.type().name());
        node.put("host", saved.host());
        node.putInt("port", saved.port());
        node.put("database", saved.database());
        node.put("username", saved.username());
        node.put("jdbcUrl", saved.jdbcUrl());
        node.put("driverClass", saved.driverClass());
        node.put("driverJar", saved.driverJar());
        node.put("authenticationMode", saved.authenticationMode().name());
        node.putBoolean("sshEnabled",saved.ssh().enabled());
        node.put("sshHost",saved.ssh().host());
        node.putInt("sshPort",saved.ssh().port());
        node.put("sshUsername",saved.ssh().username());
        node.put("sshAuthentication",saved.ssh().authentication().name());
        node.put("sshPrivateKey",saved.ssh().privateKey());
        node.put("sshKnownHosts",saved.ssh().knownHosts());
        node.putBoolean("sshStrictHostKeyChecking",saved.ssh().strictHostKeyChecking());
        node.putInt("sshLocalPort",saved.ssh().localPort());
        node.putInt("sshConnectTimeout",saved.ssh().connectTimeoutSeconds());
        node.putInt("sshKeepAlive",saved.ssh().keepAliveSeconds());
        node.putBoolean("readOnly", saved.readOnly());
        return saved;
    }

    public void delete(ConnectionProfile profile) {
        if (profile == null || profile.id().isBlank()) return;
        try { root().node(profile.id()).removeNode(); }
        catch (BackingStoreException | SecurityException ignored) {}
    }
}

