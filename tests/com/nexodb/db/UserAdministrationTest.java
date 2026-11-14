package com.nexodb.db;

import com.nexodb.model.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

public final class UserAdministrationTest {
    public static void main(String[] args) throws Exception {
        for (DatabaseType type : DatabaseType.values()) {
            Connection c = mock("NO_BACKSLASH_ESCAPES"); UserAdministration admin = new UserAdministration(c,type);
            if (type == DatabaseType.SQLITE || type == DatabaseType.MONGODB || type == DatabaseType.GENERIC) { if(admin.supported())throw new AssertionError(type); continue; }
            UserAdministration.Account account = new UserAdministration.Account("report\"x]';--", type == DatabaseType.SQLSERVER ? "SQL_LOGIN" : "local'host", "");
            if((type==DatabaseType.ORACLE || type==DatabaseType.H2) && !admin.newAccount("report_user","").name().equals("REPORT_USER"))throw new AssertionError("Native account name normalization");
            String sql = admin.createSql(account,"xY9'\\ñ!"), changed = admin.passwordSql(account,"xY9'\\ñ!");
            if (sql.contains("CASCADE") || changed.isBlank() || !admin.dropSql(account).startsWith("DROP ")) throw new AssertionError(type);
            if(type == DatabaseType.MYSQL || type == DatabaseType.MARIADB) {
                if(!sql.contains("'xY9''\\ñ!'"))throw new AssertionError("MySQL SQL mode changed password");
                UserAdministration escaped = new UserAdministration(mock(""),type);
                if(!escaped.createSql(account,"a\\b").contains("'a\\\\b'"))throw new AssertionError("MySQL escaping");
            }
            if(type==DatabaseType.POSTGRESQL && !sql.startsWith("CREATE ROLE \"report\"\"x]';--\" LOGIN PASSWORD E'"))throw new AssertionError(sql);
            if(type==DatabaseType.SQLSERVER && admin.canChangePassword(new UserAdministration.Account("domain\\user","WINDOWS_LOGIN","")))throw new AssertionError("Windows password");
            try { admin.grantSql(account,"PUBLIC",List.of("ALL PRIVILEGES; DROP DATABASE x")); throw new AssertionError("Unsafe privilege"); } catch(IllegalArgumentException expected){}
            if(admin.dropSql(account).contains("CASCADE"))throw new AssertionError("Cascading deletion");
        }
        if(UserAdministration.detect("PostgreSQL")!=DatabaseType.POSTGRESQL || UserAdministration.detect("SQLite")!=DatabaseType.SQLITE)throw new AssertionError("JDBC detection");
        Path driver=Path.of("build/h2-test.jar").toAbsolutePath();
        if(java.nio.file.Files.exists(driver)) {
            ConnectionProfile profile=new ConnectionProfile("users-test","Users test",DatabaseType.H2,"",0,"mem:users","sa","jdbc:h2:mem:users;DB_CLOSE_DELAY=-1","org.h2.Driver",driver.toString(),false);
            try(java.net.URLClassLoader loader=new java.net.URLClassLoader(new java.net.URL[]{driver.toUri().toURL()})) {
                Driver h2=(Driver)Class.forName("org.h2.Driver",true,loader).getDeclaredConstructor().newInstance();
                Properties root=new Properties();root.setProperty("user","sa");root.setProperty("password","");
                try(Connection c=ManagedConnection.wrap(h2.connect(profile.jdbcUrl(),root),()->h2.connect(profile.jdbcUrl(),root),()->{})) {
                UserAdministration admin=new UserAdministration(c,DatabaseType.H2);
                UserAdministration.Account account=new UserAdministration.Account("TEST_USER","USER","");
                execute(c,admin.createSql(account,"A'\\ñ9-password"));
                if(admin.accounts().stream().noneMatch(a->a.name().equals("TEST_USER")))throw new AssertionError("Created user missing");
                try(Statement s=c.createStatement()){s.execute("CREATE TABLE sample(id INT)"); s.execute("INSERT INTO sample VALUES(1)");}
                for(String grant:admin.grantSql(account,"PUBLIC",List.of("SELECT")))execute(c,grant);
                if(!admin.grants(account).contains("SELECT"))throw new AssertionError("Grants missing");
                execute(c,admin.passwordSql(account,"New'\\ñ9-password"));
                // H2 named memory databases belong to a classloader. Reuse the driver's
                // loader to exercise authentication against this exact fixture database.
                Properties options=new Properties();options.setProperty("user","test_user");options.setProperty("password","New'\\ñ9-password");
                try(Connection user=h2.connect("jdbc:h2:mem:users",options)) {
                    try(Statement s=user.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM sample")){r.next();if(r.getInt(1)!=1)throw new AssertionError("User SELECT");}
                    try{execute(user,"DELETE FROM sample");throw new AssertionError("Unexpected write permission");}catch(SQLException expected){}
                }
                execute(c,admin.dropSql(account));
                if(admin.accounts().stream().anyMatch(a->a.name().equals("TEST_USER")))throw new AssertionError("Delete user failed");
                if(ManagedConnection.hasActiveTransaction(c))throw new AssertionError();
                c.setAutoCommit(false);if(!ManagedConnection.hasActiveTransaction(c))throw new AssertionError();c.rollback();c.setAutoCommit(true);
                execute(c,"BEGIN");if(!ManagedConnection.hasActiveTransaction(c))throw new AssertionError("SQL BEGIN guard");execute(c,"ROLLBACK");
                }
            }
        }
        System.out.println("UserAdministrationTest: OK (six dialects, quoting, privilege whitelist, H2 account lifecycle and login, transaction guard)");
    }
    private static void execute(Connection c,String sql)throws SQLException {try(Statement s=c.createStatement()){s.execute(sql);}}
    private static Connection mock(String mode) {
        return (Connection) Proxy.newProxyInstance(UserAdministrationTest.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
            case "getCatalog" -> "current_db";
            case "getMetaData" -> Proxy.newProxyInstance(UserAdministrationTest.class.getClassLoader(),new Class[]{DatabaseMetaData.class},(p2,m2,a2)->m2.getName().equals("getDatabaseProductName")?"Unknown":null);
            case "createStatement" -> Proxy.newProxyInstance(UserAdministrationTest.class.getClassLoader(),new Class[]{Statement.class},(p2,m2,a2)->m2.getName().equals("executeQuery")?Proxy.newProxyInstance(UserAdministrationTest.class.getClassLoader(),new Class[]{ResultSet.class},(p3,m3,a3)->switch(m3.getName()){case "next"->true;case "getString"->mode;default->null;}):null);
            default -> null;
        });
    }
}
