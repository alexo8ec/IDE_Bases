package com.nexodb;

import com.nexodb.db.JdbcSession;
import com.nexodb.model.ConnectionProfile;
import com.nexodb.model.DatabaseType;
import com.nexodb.model.AuthenticationMode;

public final class LocalDatabaseProbe {
    public static void main(String[] args) throws Exception {
        ConnectionProfile profile = new ConnectionProfile(
                "probe", "Local probe", DatabaseType.MARIADB, "127.0.0.1", 3306, "", "root",
                "jdbc:mariadb://127.0.0.1:3306/?sslMode=disable", DatabaseType.MARIADB.driverClass(), "",
                AuthenticationMode.WINDOWS, true);
        try (JdbcSession session = JdbcSession.open(profile, new char[0])) {
            var metadata = session.connection().getMetaData();
            System.out.println("Connected=" + metadata.getDatabaseProductName() + " " + metadata.getDatabaseProductVersion());
            System.out.println("Driver=" + metadata.getDriverName() + " " + metadata.getDriverVersion());
            System.out.println("EffectiveType=" + session.profile().type());
        }
    }
}
