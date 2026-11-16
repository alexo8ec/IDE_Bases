package com.nexodb.db;

import com.nexodb.model.DatabaseType;
import java.util.Objects;

public final class StoredObjectServiceTest {
    public static void main(String[] args) {
        String mysql = "CREATE OR REPLACE ALGORITHM = UNDEFINED DEFINER = `user`@`host` SQL SECURITY DEFINER VIEW `db`.`ventas` AS SELECT 1 AS id";
        equal("ALTER ALGORITHM = UNDEFINED DEFINER = `user`@`host` SQL SECURITY DEFINER VIEW `db`.`ventas` AS SELECT 1 AS id",
                StoredObjectService.replacementDdl(DatabaseType.MYSQL, SchemaItem.Kind.VIEW, mysql));
        equal("ALTER ALGORITHM = UNDEFINED DEFINER = `user`@`host` SQL SECURITY DEFINER VIEW `db`.`ventas` AS SELECT 1 AS id",
                StoredObjectService.replacementDdl(DatabaseType.MARIADB, SchemaItem.Kind.VIEW, mysql));

        String portable = "CREATE VIEW ventas AS SELECT 1 AS id";
        equal("CREATE OR REPLACE VIEW ventas AS SELECT 1 AS id",
                StoredObjectService.replacementDdl(DatabaseType.POSTGRESQL, SchemaItem.Kind.VIEW, portable));
        equal("CREATE OR REPLACE VIEW ventas AS SELECT 1 AS id",
                StoredObjectService.replacementDdl(DatabaseType.ORACLE, SchemaItem.Kind.VIEW, portable));
        equal("CREATE OR REPLACE VIEW ventas AS SELECT 1 AS id",
                StoredObjectService.replacementDdl(DatabaseType.H2, SchemaItem.Kind.VIEW, portable));
        equal("CREATE OR ALTER VIEW ventas AS SELECT 1 AS id",
                StoredObjectService.replacementDdl(DatabaseType.SQLSERVER, SchemaItem.Kind.VIEW, portable));

        String event = "CREATE DEFINER = `user`@`host` EVENT `db`.`daily` ON SCHEDULE EVERY 1 DAY DO SELECT 1";
        equal("ALTER DEFINER = `user`@`host` EVENT `db`.`daily` ON SCHEDULE EVERY 1 DAY DO SELECT 1",
                StoredObjectService.replacementDdl(DatabaseType.MARIADB, SchemaItem.Kind.EVENT, event));

        rejected(DatabaseType.SQLITE, portable, "SQLite");
        rejected(DatabaseType.GENERIC, portable, "no ofrece");
        System.out.println("StoredObjectServiceTest: OK (DDL de reemplazo específico por motor)");
    }

    private static void rejected(DatabaseType type, String sql, String message) {
        try {
            StoredObjectService.replacementDdl(type, SchemaItem.Kind.VIEW, sql);
            throw new AssertionError("Se aceptó un reemplazo inseguro para " + type);
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().contains(message)) throw new AssertionError(expected.getMessage());
        }
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(expected + " != " + actual);
    }
}
