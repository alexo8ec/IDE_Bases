package com.nexodb.db;

import static com.nexodb.util.I18n.tr;
import com.nexodb.model.DatabaseType;
import java.sql.*;
import java.util.*;

/** Engine-specific account SQL. Does not fetch password hashes or grant admin by default. */
public final class UserAdministration {
    public record Account(String name, String scope, String detail) {}
    public record PrivilegeScope(String name, String label, boolean global) {
        @Override public String toString() { return label; }
    }
    public record PrivilegeState(Set<String> direct, Set<String> inherited, boolean grantOption, boolean inheritedGrantOption) {
        public PrivilegeState {
            direct = Collections.unmodifiableSet(new LinkedHashSet<>(direct));
            inherited = Collections.unmodifiableSet(new LinkedHashSet<>(inherited));
        }
    }
    public record AccountCapabilities(boolean allGlobal, boolean managesAccounts, boolean delegatesGlobally, Set<String> globalPrivileges, Set<String> databasePatterns) {
        public AccountCapabilities {
            globalPrivileges = Collections.unmodifiableSet(new LinkedHashSet<>(globalPrivileges));
            databasePatterns = Collections.unmodifiableSet(new LinkedHashSet<>(databasePatterns));
        }
    }
    public record PermissionSnapshot(List<PrivilegeScope> scopes, Map<PrivilegeScope, PrivilegeState> states, AccountCapabilities capabilities) {
        public PermissionSnapshot {
            scopes = List.copyOf(scopes);
            states = Collections.unmodifiableMap(new LinkedHashMap<>(states));
        }
    }
    private final Connection connection;
    private final DatabaseType type;
    public UserAdministration(Connection connection, DatabaseType declared) throws SQLException {
        this.connection = connection;
        this.type = declared == DatabaseType.GENERIC ? detect(connection.getMetaData().getDatabaseProductName()) : declared;
    }
    static DatabaseType detect(String product) {
        String p = product.toLowerCase(Locale.ROOT);
        if (p.contains("mariadb")) return DatabaseType.MARIADB;
        if (p.contains("mysql")) return DatabaseType.MYSQL;
        if (p.contains("postgres")) return DatabaseType.POSTGRESQL;
        if (p.contains("sql server")) return DatabaseType.SQLSERVER;
        if (p.contains("oracle")) return DatabaseType.ORACLE;
        if (p.equals("h2")) return DatabaseType.H2;
        if (p.contains("sqlite")) return DatabaseType.SQLITE;
        return DatabaseType.GENERIC;
    }
    public DatabaseType type() { return type; }
    public boolean supported() { return switch (type) { case MYSQL, MARIADB, POSTGRESQL, SQLSERVER, ORACLE, H2 -> true; default -> false; }; }
    public boolean usesHost() { return type == DatabaseType.MYSQL || type == DatabaseType.MARIADB; }
    public Account newAccount(String name, String host) {
        String normalized = type == DatabaseType.ORACLE || type == DatabaseType.H2 ? name.toUpperCase(Locale.ROOT) : name;
        return new Account(normalized, usesHost() ? host : "", "");
    }
    public String explanation() {
        return switch (type) {
            case MYSQL, MARIADB -> tr("Cada cuenta se identifica por usuario y host. Los permisos se asignan por base de datos.");
            case POSTGRESQL -> tr("Usuarios con LOGIN del servidor. Los permisos de esquema se aplican en la base actual y a las tablas existentes.");
            case SQLSERVER -> tr("Inicios de sesión del servidor. Asigna permisos para vincular la cuenta con la base actual; eliminar el login no elimina sus usuarios de base.");
            case ORACLE -> tr("Usuarios del contenedor actual. Asigna CREATE SESSION para permitir el inicio de sesión; no se eliminan objetos en cascada.");
            case H2 -> tr("Usuarios de esta base H2. Los permisos se asignan al esquema seleccionado.");
            case SQLITE -> tr("SQLite no tiene usuarios ni contraseñas nativos. Protege el archivo con los permisos del sistema operativo.");
            case MONGODB -> tr("Atlas SQL permite consultas de solo lectura. Administra los usuarios de MongoDB en Atlas: Database Access.");
            default -> tr("JDBC no define una API universal para crear usuarios. Selecciona el motor real en la conexión para habilitar su administración.");
        };
    }
    public List<Account> accounts() throws SQLException {
        if (!supported()) return List.of();
        String sql = switch (type) {
            case MYSQL, MARIADB -> "SELECT User, Host, plugin FROM mysql.user ORDER BY User, Host";
            case POSTGRESQL -> "SELECT rolname, CASE WHEN rolsuper THEN 'SUPERUSER' ELSE 'LOGIN' END, CASE WHEN rolvaliduntil IS NULL THEN '' ELSE rolvaliduntil::text END FROM pg_roles WHERE rolcanlogin ORDER BY rolname";
            case SQLSERVER -> "SELECT name, type_desc, CASE WHEN is_disabled=1 THEN 'DISABLED' ELSE 'ENABLED' END FROM sys.server_principals WHERE type IN ('S','U','G') ORDER BY name";
            case ORACLE -> "SELECT username, account_status, authentication_type FROM dba_users ORDER BY username";
            case H2 -> "SELECT USER_NAME, CASE WHEN IS_ADMIN THEN 'ADMIN' ELSE 'USER' END, '' FROM INFORMATION_SCHEMA.USERS ORDER BY USER_NAME";
            default -> throw new IllegalStateException();
        };
        List<Account> result = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) result.add(new Account(r.getString(1), Objects.toString(r.getString(2), ""), Objects.toString(r.getString(3), "")));
        }
        return result;
    }
    public String display(Account a) { return usesHost() ? a.name() + "@" + a.scope() : a.name(); }
    private String principal(Account a) throws SQLException { return usesHost() ? literal(a.name()) + "@" + literal(a.scope()) : identifier(a.name()); }
    public String createSql(Account a, String password) throws SQLException {
        requireSupported();
        a = newAccount(a.name(), a.scope());
        return switch (type) {
            case POSTGRESQL -> "CREATE ROLE " + principal(a) + " LOGIN PASSWORD " + literal(password);
            case SQLSERVER -> "CREATE LOGIN " + principal(a) + " WITH PASSWORD = " + literal(password) + ", CHECK_POLICY = ON";
            case H2 -> "CREATE USER " + principal(a) + " PASSWORD " + literal(password);
            case ORACLE -> "CREATE USER " + principal(a) + " IDENTIFIED BY " + oraclePassword(password);
            default -> "CREATE USER " + principal(a) + " IDENTIFIED BY " + literal(password);
        };
    }
    public boolean canChangePassword(Account a) { return type != DatabaseType.SQLSERVER || a.scope().equals("SQL_LOGIN"); }
    public String passwordSql(Account a, String password) throws SQLException {
        requireSupported(); if (!canChangePassword(a)) throw new IllegalArgumentException(tr("La contraseña de esta cuenta se administra en Windows."));
        return switch (type) {
            case POSTGRESQL -> "ALTER ROLE " + principal(a) + " PASSWORD " + literal(password);
            case SQLSERVER -> "ALTER LOGIN " + principal(a) + " WITH PASSWORD = " + literal(password);
            case H2 -> "ALTER USER " + principal(a) + " SET PASSWORD " + literal(password);
            case ORACLE -> "ALTER USER " + principal(a) + " IDENTIFIED BY " + oraclePassword(password);
            default -> "ALTER USER " + principal(a) + " IDENTIFIED BY " + literal(password);
        };
    }
    public String dropSql(Account a) throws SQLException { requireSupported(); return (type == DatabaseType.POSTGRESQL ? "DROP ROLE " : type == DatabaseType.SQLSERVER ? "DROP LOGIN " : "DROP USER ") + principal(a); }
    public List<String> scopes() throws SQLException {
        if (type == DatabaseType.ORACLE) return List.of(tr("Contenedor actual"));
        if (type == DatabaseType.SQLSERVER) return List.of(connection.getCatalog());
        List<String> result = new ArrayList<>();
        try (ResultSet r = usesHost() ? connection.getMetaData().getCatalogs() : connection.getMetaData().getSchemas()) {
            while (r.next()) result.add(r.getString(1));
        }
        String current = usesHost() ? connection.getCatalog() : connection.getSchema();
        if (current != null && result.remove(current)) result.add(0,current);
        return result;
    }
    public List<PrivilegeScope> privilegeScopes() throws SQLException {
        List<PrivilegeScope> result = new ArrayList<>();
        for (String scope : scopes()) result.add(new PrivilegeScope(scope, scope, false));
        if (usesHost()) result.add(new PrivilegeScope("*.*", tr("Todo el servidor (*.*) · avanzado"), true));
        return result;
    }
    public List<String> privileges() {
        return switch (type) {
            case MYSQL, MARIADB -> List.of("SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "REFERENCES", "INDEX", "ALTER", "CREATE TEMPORARY TABLES", "LOCK TABLES", "EXECUTE", "CREATE VIEW", "SHOW VIEW", "CREATE ROUTINE", "ALTER ROUTINE", "EVENT", "TRIGGER");
            case POSTGRESQL -> List.of("USAGE", "CREATE", "SELECT", "INSERT", "UPDATE", "DELETE", "REFERENCES", "TRIGGER");
            case SQLSERVER -> List.of("db_datareader", "db_datawriter", "db_ddladmin");
            case ORACLE -> List.of("CREATE SESSION", "CREATE TABLE", "CREATE VIEW", "CREATE SEQUENCE", "CREATE PROCEDURE");
            case H2 -> List.of("SELECT", "INSERT", "UPDATE", "DELETE");
            default -> List.of();
        };
    }
    public List<String> privileges(PrivilegeScope scope) {
        List<String> result = new ArrayList<>(privileges());
        if (usesHost() && scope.global()) result.add("CREATE USER");
        return result;
    }

    /** Reads the account's direct grants once and derives every selectable MySQL/MariaDB scope. */
    public PermissionSnapshot permissionSnapshot(Account account) throws SQLException {
        if (!usesHost()) throw new IllegalStateException(tr("La edición visual de permisos existentes está disponible para MySQL y MariaDB."));
        List<String> grants = mysqlGrantLines(account);
        List<PrivilegeScope> scopes = new ArrayList<>(privilegeScopes());
        Set<String> known = new HashSet<>(); for (PrivilegeScope scope : scopes) known.add(scope.name().toLowerCase(Locale.ROOT));
        for (String line : grants) {
            ParsedGrant parsed = parseMysqlGrant(line); String pattern = parsed == null ? null : mysqlDatabaseTarget(parsed.target());
            if (pattern != null && hasMysqlWildcard(pattern) && known.add(pattern.toLowerCase(Locale.ROOT)))
                scopes.add(scopes.size() - 1, new PrivilegeScope(pattern, tr("Patrón de bases: ") + parsed.target(), false));
        }
        Map<PrivilegeScope, PrivilegeState> states = new LinkedHashMap<>();
        for (PrivilegeScope scope : scopes) states.put(scope, mysqlPrivilegeState(scope, grants));
        return new PermissionSnapshot(scopes, states, mysqlCapabilities(grants));
    }

    /** Produces only the GRANT/REVOKE statements needed to reach the selected direct state. */
    public List<String> updatePrivilegeSql(Account account, PrivilegeScope scope, Collection<String> selected, boolean grantOption, PrivilegeState before) throws SQLException {
        if (!usesHost()) throw new IllegalStateException(tr("La edición visual de permisos existentes está disponible para MySQL y MariaDB."));
        Set<String> allowed = new LinkedHashSet<>(privileges(scope));
        Set<String> desired = normalizePrivileges(selected);
        if (!allowed.containsAll(desired)) throw new IllegalArgumentException(tr("Se seleccionó un permiso no válido."));
        desired.removeAll(before.inherited());
        Set<String> added = new LinkedHashSet<>(desired); added.removeAll(before.direct());
        Set<String> removed = new LinkedHashSet<>(before.direct()); removed.removeAll(desired); removed.retainAll(allowed);
        String target = scope.global() ? "*.*" : identifier(scope.name()) + ".*";
        String who = principal(account);
        List<String> sql = new ArrayList<>();
        if (!removed.isEmpty()) sql.add("REVOKE " + String.join(", ", removed) + " ON " + target + " FROM " + who);
        if (!added.isEmpty()) sql.add("GRANT " + String.join(", ", added) + " ON " + target + " TO " + who);
        if (before.grantOption() != grantOption) {
            sql.add(grantOption ? "GRANT USAGE ON " + target + " TO " + who + " WITH GRANT OPTION"
                    : "REVOKE GRANT OPTION ON " + target + " FROM " + who);
        }
        return sql;
    }

    private List<String> mysqlGrantLines(Account account) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SHOW GRANTS FOR " + principal(account))) {
            while (rows.next()) result.add(Objects.toString(rows.getString(1), ""));
        }
        return result;
    }

    private PrivilegeState mysqlPrivilegeState(PrivilegeScope scope, List<String> grants) {
        Set<String> allowed = new LinkedHashSet<>(privileges(scope));
        Set<String> direct = new LinkedHashSet<>(), inherited = new LinkedHashSet<>();
        boolean directGrant = false, inheritedGrant = false;
        String expected = scope.global() ? "*.*" : mysqlTarget(scope.name());
        for (String line : grants) {
            ParsedGrant parsed = parseMysqlGrant(line);
            if (parsed == null) continue;
            boolean exact = parsed.target().equalsIgnoreCase(expected);
            boolean globalParent = !scope.global() && parsed.target().equals("*.*");
            String pattern = mysqlDatabaseTarget(parsed.target());
            boolean patternParent = !scope.global() && !exact && pattern != null && hasMysqlWildcard(pattern) && mysqlPatternMatches(pattern, scope.name());
            if (!exact && !globalParent && !patternParent) continue;
            Set<String> destination = exact ? direct : inherited;
            if (parsed.all()) destination.addAll(allowed);
            else for (String privilege : parsed.privileges()) if (allowed.contains(privilege)) destination.add(privilege);
            if (exact && parsed.grantOption()) directGrant = true;
            if ((globalParent || patternParent) && parsed.grantOption()) inheritedGrant = true;
        }
        inherited.removeAll(direct);
        return new PrivilegeState(direct, inherited, directGrant, inheritedGrant);
    }

    private AccountCapabilities mysqlCapabilities(List<String> grants) {
        Set<String> global = new LinkedHashSet<>(), patterns = new LinkedHashSet<>(); boolean all = false, delegate = false;
        for (String line : grants) {
            ParsedGrant parsed = parseMysqlGrant(line);
            if (parsed == null) continue;
            String pattern = mysqlDatabaseTarget(parsed.target()); if (pattern != null && hasMysqlWildcard(pattern)) patterns.add(parsed.target());
            if (parsed.target().equals("*.*")) { all |= parsed.all(); delegate |= parsed.grantOption(); global.addAll(parsed.privileges()); }
        }
        return new AccountCapabilities(all, all || global.contains("CREATE USER"), delegate, global, patterns);
    }

    private ParsedGrant parseMysqlGrant(String sql) {
        String upper = sql.toUpperCase(Locale.ROOT); int start = upper.indexOf("GRANT "), on = upper.indexOf(" ON "), to = upper.indexOf(" TO ", on + 4);
        if (start != 0 || on < 0 || to < 0) return null; // Role grants do not contain ON and are intentionally ignored here.
        String privilegeText = upper.substring(6, on).trim();
        Set<String> parsed = normalizePrivileges(Arrays.asList(privilegeText.split("\\s*,\\s*")));
        boolean all = parsed.remove("ALL PRIVILEGES") | parsed.remove("ALL");
        parsed.remove("USAGE"); parsed.remove("GRANT OPTION");
        return new ParsedGrant(sql.substring(on + 4, to).trim(), parsed, all, upper.contains(" WITH GRANT OPTION"));
    }

    private static Set<String> normalizePrivileges(Collection<String> values) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) if (value != null && !value.isBlank()) result.add(value.trim().toUpperCase(Locale.ROOT));
        return result;
    }

    private static String mysqlTarget(String database) { return "`" + database.replace("`", "``") + "`.*"; }
    private static String mysqlDatabaseTarget(String target) {
        if (!target.startsWith("`") || !target.endsWith("`.*")) return null;
        return target.substring(1, target.length() - 3).replace("``", "`");
    }
    private static boolean hasMysqlWildcard(String value) {
        boolean escaped = false; for (int i=0;i<value.length();i++) { char c=value.charAt(i); if (!escaped && (c=='%' || c=='_')) return true; escaped=!escaped&&c=='\\'; if(c!='\\')escaped=false; } return false;
    }
    private static boolean mysqlPatternMatches(String pattern, String value) {
        StringBuilder regex=new StringBuilder("(?is)^"); boolean escaped=false;
        for(int i=0;i<pattern.length();i++){char c=pattern.charAt(i);if(escaped){regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));escaped=false;}else if(c=='\\')escaped=true;else if(c=='%')regex.append(".*");else if(c=='_')regex.append('.');else regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));}
        if(escaped)regex.append("\\\\");return value.matches(regex.append('$').toString());
    }
    private record ParsedGrant(String target, Set<String> privileges, boolean all, boolean grantOption) {}
    public List<String> grantSql(Account a, String scope, List<String> chosen) throws SQLException {
        requireSupported();
        if (chosen.isEmpty() || !privileges().containsAll(chosen)) throw new IllegalArgumentException(tr("Selecciona al menos un permiso válido."));
        String who = principal(a);
        if (usesHost()) return List.of("GRANT " + String.join(", ", chosen) + " ON " + identifier(scope) + ".* TO " + who);
        if (type == DatabaseType.ORACLE) return List.of("GRANT " + String.join(", ", chosen) + " TO " + who);
        if (type == DatabaseType.H2) return List.of("GRANT " + String.join(", ", chosen) + " ON SCHEMA " + identifier(scope) + " TO " + who);
        List<String> sql = new ArrayList<>();
        if (type == DatabaseType.SQLSERVER) {
            if (!Objects.equals(scope, connection.getCatalog())) throw new IllegalArgumentException(tr("Selecciona la base actual."));
            // Resolve an existing mapping by SID, including mappings with a different user name.
            String n = literal(a.name());
            for (String role : chosen) sql.add("DECLARE @u sysname; SELECT @u=name FROM sys.database_principals WHERE sid=SUSER_SID(" + n + "); "
                    + "IF @u IS NULL BEGIN CREATE USER " + who + " FOR LOGIN " + who + "; SET @u=" + n + "; END; "
                    + "DECLARE @q nvarchar(max)=N'ALTER ROLE " + identifier(role) + " ADD MEMBER '+QUOTENAME(@u); EXEC sys.sp_executesql @q;");
            return sql;
        }
        List<String> schema = chosen.stream().filter(p -> p.equals("USAGE") || p.equals("CREATE")).toList();
        List<String> tables = chosen.stream().filter(p -> !schema.contains(p)).toList();
        if (!schema.isEmpty()) sql.add("GRANT " + String.join(", ", schema) + " ON SCHEMA " + identifier(scope) + " TO " + who);
        if (!tables.isEmpty()) sql.add("GRANT " + String.join(", ", tables) + " ON ALL TABLES IN SCHEMA " + identifier(scope) + " TO " + who);
        return sql;
    }
    public String grants(Account a) throws SQLException {
        requireSupported(); String n = literal(a.name());
        List<String> queries = switch (type) {
            case MYSQL, MARIADB -> List.of("SHOW GRANTS FOR " + principal(a));
            case POSTGRESQL -> List.of("SELECT r.rolname AS role FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.roleid JOIN pg_roles u ON u.oid=m.member WHERE u.rolname=" + n,
                    "SELECT table_schema, table_name, privilege_type, is_grantable FROM information_schema.table_privileges WHERE grantee=" + n,
                    "SELECT nspname AS schema, has_schema_privilege(" + n + ",oid,'USAGE') AS can_use, has_schema_privilege(" + n + ",oid,'CREATE') AS can_create FROM pg_namespace");
            case SQLSERVER -> List.of("SELECT p.state_desc,p.permission_name FROM sys.server_permissions p JOIN sys.server_principals u ON u.principal_id=p.grantee_principal_id WHERE u.name=" + n,
                    "SELECT r.name AS server_role FROM sys.server_role_members m JOIN sys.server_principals r ON r.principal_id=m.role_principal_id JOIN sys.server_principals u ON u.principal_id=m.member_principal_id WHERE u.name=" + n,
                    "SELECT r.name AS database_role FROM sys.database_role_members m JOIN sys.database_principals r ON r.principal_id=m.role_principal_id JOIN sys.database_principals u ON u.principal_id=m.member_principal_id WHERE u.sid=SUSER_SID(" + n + ")",
                    "SELECT p.state_desc,p.permission_name,p.class_desc,p.major_id FROM sys.database_permissions p JOIN sys.database_principals u ON u.principal_id=p.grantee_principal_id WHERE u.sid=SUSER_SID(" + n + ")");
            case ORACLE -> List.of("SELECT privilege, admin_option FROM dba_sys_privs WHERE grantee=" + n, "SELECT granted_role, admin_option FROM dba_role_privs WHERE grantee=" + n, "SELECT owner, table_name, privilege, grantable FROM dba_tab_privs WHERE grantee=" + n);
            case H2 -> List.of("SELECT * FROM INFORMATION_SCHEMA.RIGHTS WHERE GRANTEE=" + n);
            default -> throw new IllegalStateException();
        };
        StringBuilder text = new StringBuilder();
        for (String query : queries) try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery(query)) {
            ResultSetMetaData meta = r.getMetaData();
            for (int i=1;i<=meta.getColumnCount();i++) text.append(i==1 ? "" : " | ").append(meta.getColumnLabel(i)); text.append('\n');
            while (r.next()) { for (int i=1;i<=meta.getColumnCount();i++) text.append(i==1 ? "" : " | ").append(Objects.toString(r.getString(i), "")); text.append('\n'); } text.append('\n');
        }
        // MariaDB versions may include authentication clauses in SHOW GRANTS.
        return text.toString().replaceAll("(?im)\\s+IDENTIFIED\\s+[^\\r\\n]*", " [" + tr("Autenticación oculta") + "]");
    }
    String literal(String value) throws SQLException {
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException(tr("Caracteres no permitidos."));
        if (usesHost()) {
            boolean noBackslash;
            try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT @@SESSION.sql_mode")) { r.next(); noBackslash = Arrays.asList(r.getString(1).split(",")).contains("NO_BACKSLASH_ESCAPES"); }
            return MysqlTableDesign.literal(value, noBackslash);
        }
        if (type == DatabaseType.POSTGRESQL) return "E'" + value.replace("\\", "\\\\").replace("'", "''") + "'";
        return (type == DatabaseType.SQLSERVER ? "N" : "") + "'" + value.replace("'", "''") + "'";
    }
    String identifier(String name) {
        if (name == null || name.isBlank() || name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(tr("Indica un nombre válido."));
        if (usesHost()) return "`" + name.replace("`", "``") + "`";
        if (type == DatabaseType.SQLSERVER) return "[" + name.replace("]", "]]") + "]";
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
    private static String oraclePassword(String password) {
        if (password.indexOf('"') >= 0 || password.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(tr("Oracle no permite comillas dobles ni caracteres de control en la contraseña."));
        return "\"" + password + "\"";
    }
    private void requireSupported() { if (!supported()) throw new IllegalStateException(explanation()); }
}
