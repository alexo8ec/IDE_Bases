# Conexiones y usuarios

## Abrir una base SQLite

SQLite abre un archivo local; no hace falta subirlo, instalar un servidor ni indicar usuario o contraseña.

1. Selecciona **Nueva conexión** y el motor **SQLite**.
2. Pulsa **Elegir archivo…** y selecciona el archivo principal con extensión `.sqlite`, `.sqlite3`, `.db` u otra extensión válida.
3. Pon un nombre que identifique esa base. **Solo lectura** está activado al elegir SQLite; desactívalo en el perfil si necesitas permitir cambios.
4. Pulsa **Probar** o **Guardar y conectar**. Si falta el controlador, AdisDB descarga el adaptador verificado.
5. Abre **Objetos → main → Tablas** para explorar los datos. Cada archivo SQLite se guarda como una conexión independiente.

Puedes escribir una ruta completa, por ejemplo `C:/xampp/htdocs/perfectgym/database/database.sqlite`. No se crea una base vacía si la ruta está equivocada. El programa abre el archivo en su ubicación original; una conexión con escritura modifica ese mismo archivo. Los archivos cifrados requieren un controlador compatible y no se aceptan como SQLite ordinario. Los archivos `-wal` y `-shm` son auxiliares, no bases independientes.

El formulario adapta sus campos al motor. MySQL/MariaDB muestran autenticación integrada cuando está disponible; PostgreSQL permite dejar la base vacía para elegirla; Oracle pide el nombre de servicio y ofrece la URL avanzada para SID/TNS/SSL. H2 pide ubicación o modo (`~/datos`, `mem:pruebas`, `tcp://servidor/~/datos`) y sus propias credenciales. JDBC genérico utiliza URL, clase y archivo del controlador, con credenciales opcionales. Al cambiar de motor se conservan los campos no secretos de cada borrador; la contraseña escrita se limpia.

## Administrar usuarios

Abre **Herramientas avanzadas → Administrar usuarios…** con una conexión activa. La cuenta conectada debe disponer de los permisos administrativos del servidor. El perfil de solo lectura permite consultar, pero desactiva los cambios. Se muestra un buscador de cuentas y las acciones disponibles para la selección.

**Nuevo usuario** y **Cambiar contraseña** permiten generar una contraseña local de 20 caracteres por defecto, elegir una longitud entre 12 y 64 y activar símbolos. También puedes escribirla, confirmarla, mostrarla y copiarla. La contraseña no se guarda en el perfil ni en el historial; cópiala antes de aplicar. La política del servidor puede exigir condiciones adicionales. Cada cambio remoto explica su efecto y requiere confirmar **Sí**; las lecturas no piden confirmación. Si cambias la contraseña de la cuenta con la que estás conectado, actualiza sus credenciales de conexión antes de reconectar.

Las cuentas nuevas de Oracle y H2 se crean en mayúsculas, siguiendo su convención de nombres. La confirmación muestra ese nombre definitivo; al cambiar o eliminar una cuenta existente se conserva exactamente su nombre original.

| Motor | Administración disponible | Ámbito de los permisos ofrecidos |
| --- | --- | --- |
| MySQL / MariaDB | Crear, cambiar contraseña, eliminar, consultar y asignar permisos | Cuenta `usuario@host`; base seleccionada |
| PostgreSQL | Roles con `LOGIN`, contraseña, eliminación y permisos | Esquema de la base actual; permisos de tabla sobre las tablas existentes, sin alterar permisos predeterminados futuros |
| SQL Server | Logins del servidor y sus permisos/roles | Vincula el login con un usuario de la base actual y ofrece `db_datareader`, `db_datawriter`, `db_ddladmin`; las contraseñas Windows se administran en Windows |
| Oracle | Usuarios del contenedor actual, contraseña, eliminación y permisos | Privilegios del sistema expresamente elegidos; `CREATE SESSION` permite iniciar sesión; crear objetos también puede requerir cuota de tablespace |
| H2 | Usuarios, contraseña, eliminación y permisos | Esquema seleccionado |
| SQLite | Explicación del acceso al archivo | No dispone de usuarios nativos; se protege con permisos del sistema operativo |
| MongoDB Atlas SQL | Explicación de la gestión de acceso | El adaptador SQL es de solo lectura; administra cuentas en Atlas → Database Access |
| JDBC genérico | Detecta MySQL, MariaDB, PostgreSQL, SQL Server, Oracle, H2 y SQLite | No inventa comandos de usuarios para controladores desconocidos |

No se eliminan objetos en cascada. En SQL Server, eliminar un login conserva sus usuarios de base de datos. Las operaciones de varias sentencias informan cuántas se aplicaron si hay un error; no se repiten automáticamente. Antes de administrar cuentas debes finalizar cualquier transacción y activar Auto-commit. Se restaura el modo de lectura de la sesión al terminar. Los permisos mostrados pueden estar limitados por la visibilidad que concede el servidor y no representan una auditoría exhaustiva de todos los permisos heredados.

Referencias: [PostgreSQL CREATE ROLE](https://www.postgresql.org/docs/current/sql-createrole.html), [SQL Server CREATE LOGIN](https://learn.microsoft.com/en-us/sql/t-sql/statements/create-login-transact-sql), [Oracle: creación de cuentas](https://docs.oracle.com/en/database/oracle/oracle-database/19/dbseg/creating-user-accounts.html), [comandos H2](https://h2database.com/html/commands.html), [controlador SQLite](https://github.com/xerial/sqlite-jdbc), [MongoDB SQL Interface](https://www.mongodb.com/docs/sql-interface/).

La versión de escritorio 0.14.3 incluye estos formularios. Android mantiene su versión 0.14.2 y sus funciones actuales; esta entrega no añade administración de usuarios en la interfaz móvil.
