# NexoDB Studio

La versión **0.12.0** abre los scripts generados por comparaciones y las plantillas SQL en **pestañas de consulta independientes**. El SQL anterior se conserva y no forma parte del nuevo script. **Consulta → Nueva consulta**, **Ctrl+N** o **+** crean otra pestaña; cada pestaña tiene su botón de cierre. El borrador local conserva todas las pestañas y sus cursores al reiniciar, sin ejecutar su contenido. Las consultas nuevas empiezan vacías; al recuperar un borrador antiguo se elimina únicamente la cabecera de ayuda predeterminada conocida, conservando el resto del SQL y los comentarios del usuario.

El resaltado, las mayúsculas automáticas y el autocompletado comparten un vocabulario ampliado de palabras SQL, tipos y funciones, incluyendo **YEAR** y **DECLARE**. Se complementa con las palabras y funciones publicadas por el controlador JDBC y, cuando está disponible, con `INFORMATION_SCHEMA.KEYWORDS` de MySQL/MariaDB. Las cadenas, los comentarios y los identificadores entre comillas no se convierten en palabras clave.

**Base de datos → Respaldo SQL…** e **Importar / restaurar SQL…** permiten respaldar y restaurar tablas o bases MySQL/MariaDB. También están en el menú contextual de la conexión, la base y la tabla. El respaldo ofrece casillas por objeto y categoría, estructura y datos / solo estructura / solo datos, y opciones avanzadas para CREATE DATABASE/USE, DROP, DEFINER, claves foráneas y una lectura consistente de InnoDB. Incluye todas las filas, sin aplicar el límite del visor. El archivo de destino se reemplaza únicamente al completar el respaldo.

Para restaurar, selecciona un archivo **SQL UTF-8**, la base de destino y **Revisar archivo**. Se muestra una vista previa y el número de sentencias que modifican o eliminan datos u objetos; **Importar SQL** solicita revisar el servidor y el destino antes de ejecutar. Por defecto redirige las referencias de la base de origen hacia el destino seleccionado y omite CREATE DATABASE/USE del archivo. La opción avanzada de respetar nombres permite archivos de varias bases. Un archivo que cambió después de revisarse debe revisarse otra vez. La importación usa una conexión independiente, admite DELIMITER y dumps nativos MySQL/MariaDB, se detiene al primer error y muestra su línea.

Las operaciones no cambian la transacción del editor. La importación requiere una conexión habilitada para escritura. Los cambios DDL de MySQL/MariaDB pueden confirmarse inmediatamente y permanecer tras un error o cancelación; el asistente lo indica antes de restaurar. Selecciona las dependencias necesarias de vistas y rutinas; los permisos determinan qué objetos se pueden respaldar. Una lectura consistente cubre InnoDB, requiere evitar cambios DDL durante el respaldo y no garantiza consistencia en otros motores. Los respaldos contienen los datos y objetos seleccionados, sin usuarios, privilegios del servidor ni configuración del servidor. Una sentencia individual admite hasta 32 MB; el respaldo genera INSERT por lotes y avisa si una fila supera el límite. Los paquetes macOS siguen pendientes de firma Developer ID y notarización; no se han eliminado esas advertencias.

La versión **0.11.4** activa la edición de resultados con doble clic o F2. Las casillas resaltan toda la fila, conservando su identidad al ordenar o filtrar. El clic derecho, la flecha de la celda o el botón ▾ abren un menú con inserción, duplicación, eliminación de filas marcadas, guardado/cancelación, NULL, cadena vacía, valor predeterminado, filtros, copia y exportación. Las operaciones permanecen pendientes hasta Guardar; el lote se aplica en una transacción y las eliminaciones se confirman antes de ejecutarse.

El editor guarda automáticamente todo el texto SQL, incluidas las sentencias no ejecutadas, y recupera su contenido y posición del cursor en el siguiente inicio. Guarda un borrador local por usuario junto a las preferencias; utiliza UTF-8 y reemplazo atómico. También guarda antes de cerrar o instalar una actualización. Recuperar el texto no abre conexiones ni ejecuta consultas.

Para publicar con identidad verificable, consulta la [guía de firma Windows y notarización macOS](docs/signing.md). Los paquetes generados sin certificados permanecen sin firma y pueden mostrar advertencias del sistema. La firma no garantiza eliminar inmediatamente las alertas de reputación de SmartScreen.

La versión **0.11.3** convierte los resultados de consultas en una cuadrícula con barra de acciones: **Editar, Guardar, Descartar, NULL y Recargar**. Las celdas modificadas se resaltan y se guardan juntas al pulsar Guardar; respeta el modo Auto-commit y las transacciones manuales. La edición requiere un SELECT de una tabla física con todas las columnas de su clave primaria. Un cambio concurrente provoca la reversión del lote, conservando los cambios pendientes para corregirlos o descartarlos.

Las casillas iniciales permiten marcar filas; las cabeceras seleccionan columnas completas. El menú contextual ofrece copia de filas/columnas, filtros por valor o por columna, ordenación y acceso al editor para agregar/eliminar campos de la tabla. Estos cambios de estructura se revisan en su formulario y la eliminación solicita confirmación. Las filas marcadas conservan su identidad al ordenar o filtrar.

El autocompletado SQL incluye las columnas de las tablas utilizadas en SELECT, WHERE, UPDATE/SET e INSERT. Reconoce `tabla.` y `alias.`, carga los metadatos en segundo plano y conserva una caché breve por conexión y base. **Actualizar objetos** y los cambios de estructura invalidan la caché. Mantiene Ctrl+Espacio y la aceptación con clic, Enter o Tab.

Para reproducir las pruebas de escritura y de la cuadrícula en Windows, ejecuta `./tools/test-query-edit.ps1`: descarga un controlador H2 oficial verificado y ejecuta las pruebas exclusivamente sobre bases temporales en memoria, sin utilizar perfiles de conexión guardados.

Para las pruebas reales de respaldo/restauración, `./tools/test-sql-transfer.ps1` requiere MariaDB instalado (por defecto `C:\Program Files\MariaDB 12.3\bin`, configurable mediante `-MariaDbBin`). Crea una instancia temporal propia bajo `build`, escucha únicamente en `127.0.0.1:43179`, rechaza un puerto ocupado y no carga configuraciones ni perfiles personales. Las pruebas verifican el directorio del servidor antes de escribir y cierran su proceso al terminar; no instalan servicios. Comprueban más de 1.200 filas, Unicode, binarios, precisión decimal, fechas cero, IDs cero, columnas generadas, geometría, vistas dependientes, procedimientos, funciones, triggers, eventos y compatibilidad con un dump nativo independiente. También verifican cancelación, archivos modificados tras la revisión y detención/reversión al primer error.

La versión **0.11.2** añade selección por casillas de tablas y vistas antes de comparar esquemas, con búsqueda y acciones Todas/Ninguna. La comparación solo lee y compara los objetos marcados. Conserva las bases por conexión y las tablas seleccionadas durante la sesión, incluso al cerrar y volver a abrir la comparación. La lectura de columnas se agrupa para evitar una consulta por tabla y las conexiones independientes se leen en paralelo. La prueba de conexión muestra un resultado destacado, con servidor, base utilizada y tiempo de respuesta.

Esta entrega incluye instalador **EXE para Windows x64**, **DEB para Linux x64 y ARM64**, y **aplicación macOS con instalador `.command` para Intel y Apple Silicon**. Los cinco paquetes incluyen Java 17 para su sistema y arquitectura. Consulta los nombres e instrucciones en la [guía de distribución](docs/multiplatform.md). Los paquetes de macOS no están firmados ni notarizados.

La versión 0.11.1 aplica el idioma inmediatamente al guardar las preferencias. Actualiza los menús, botones, pestañas y ayudas de las ventanas abiertas sin cerrar conexiones ni modificar consultas, datos o nombres de objetos. También permite volver a español o usar el idioma del sistema durante la misma sesión.

La versión 0.11.0 añade **Preferencias → Idioma y apariencia** con Español, English, idioma del sistema y temas claro, oscuro o según el sistema. El editor SQL adapta su fondo y resaltado al tema sin modificar las consultas. Las preferencias se guardan por usuario.

El actualizador comparte la descarga HTTPS segura con los controladores: reintenta TLS 1.2 y la conexión nativa cuando falla el handshake, mantiene la validación de certificados y la comprobación SHA-256, y distingue errores de red, TLS y canal no publicado (HTTP 404). Los manifiestos admiten paquetes por sistema y arquitectura.

Hay un paquete Java universal instalable por usuario en **Windows, Linux y macOS** (requiere Java 17+), además de scripts para generar instaladores nativos con runtime en cada sistema. Consulta la [guía de distribución multiplataforma](docs/multiplatform.md) para instalación, requisitos, temas, idiomas y publicación de actualizaciones.

La versión 0.10.6 corrige las conexiones PostgreSQL sin base indicada: ya no utiliza el nombre del usuario como base. **Probar** y **Guardar y conectar** muestran un selector con las bases disponibles para ese usuario, filtradas por permiso `CONNECT` y sin plantillas. Al elegir una base se abre una conexión real a ella; el editor, el buscador y el autocompletado trabajan sobre esa base.

La versión 0.6.0 incorpora un explorador de objetos con menús contextuales, pestañas para datos, columnas, índices y DDL, navegación de vistas, procedimientos, funciones, triggers y eventos, además de comparación de esquemas con generación de scripts revisables.

La versión 0.6.1 añade selección persistente de la base activa, reconocimiento de `USE`, edición de celdas con guardado inmediato mediante clave primaria, administración de usuarios MySQL/MariaDB e indicadores de progreso durante las operaciones.

La versión 0.6.2 incorpora autocompletado SQL automático y manual con `Ctrl+Espacio`, sugerencias de palabras clave, bases, tablas y vistas, además de un buscador global de objetos sobre el explorador.

La versión 0.7.0 incorpora comparación de datos entre dos bases visibles en la conexión activa. Utiliza claves primarias para detectar filas faltantes, adicionales o modificadas, ofrece normalización de textos y genera scripts revisables de inserción y actualización.

La versión 0.7.1 corrige el foco del autocompletado SQL. Las sugerencias se aceptan con un clic, `Enter` o `Tab` sin sacar el cursor del editor.

La versión 0.7.2 conserva las fechas cero de MySQL como texto durante consultas y comparaciones, evitando el error `Zero date value prohibited`.

La versión 0.7.3 limita la búsqueda a la base activa e incluye tablas, vistas, procedimientos, funciones, triggers y eventos. La comparación de datos muestra, abre y guarda el script de sincronización.

La versión 0.7.4 permite seleccionar individualmente las tablas que se compararán, añade la acción global `F5`, inicia el selector con “Seleccione una base…” y elimina comentarios redundantes del script de sincronización.

La versión 0.9.0 mantiene varias conexiones abiertas y separadas en el explorador de objetos. También puede recordar la contraseña cifrada con Windows DPAPI para el usuario actual, sin guardarla en el perfil ni mostrarla después.

La versión 0.10.0 exporta resultados y tablas a CSV, TSV, JSON, XML, HTML y SQL, permite copiar tablas entre bases o conexiones abiertas y añade resaltado de sintaxis con conversión automática de palabras reservadas a mayúsculas. Expandir una base con doble clic ya no la selecciona en el editor SQL. El editor de tablas permite crear, modificar y eliminar columnas; crear índices normales o únicos; y administrar claves foráneas con reglas de actualización y eliminación.

Las comparaciones de estructura y datos permiten elegir por separado la instancia y la base de origen y destino. En la comparación de datos se pueden incluir todas las tablas o escogerlas individualmente. Los índices y relaciones existentes se pueden modificar desde sus propias pestañas. El buscador de objetos incluye una X y admite Esc para limpiar el filtro y volver al árbol completo.

La versión 0.10.4 añade MongoDB mediante su **Atlas SQL Interface** y el controlador JDBC oficial completo. También incorpora un selector SSL/TLS visible con modos automático, requerido, verificación completa y sin cifrado, además de un reintento guiado cuando PostgreSQL rechaza el handshake.

La versión 0.10.5 corrige la descarga bajo demanda de controladores JDBC. Usa el servidor canónico de Maven Central, reintenta con TLS 1.2 y, si Java es bloqueado por el proxy o antivirus, utiliza la conexión HTTPS nativa de Windows. PostgreSQL no se incluye previamente: se descarga al pulsar **Verificar / descargar driver** o al iniciar la primera conexión, y se valida con SHA-256 antes de usarlo.

Canal estable de actualizaciones: `https://raw.githubusercontent.com/alexo8ec/IDE_Bases/main/dist/update-manifest.json`. El manifiesto, los paquetes por plataforma y el EXE vigente se publican desde `dist` en el repositorio público. Los demás archivos generados permanecen excluidos de Git.

La versión 0.10.3 añadió en el formulario de conexión el botón **Verificar / descargar driver**. Comprueba el archivo, su firma y la clase JDBC sin abrir una conexión; si falta, lo descarga y muestra el progreso. También diferencia los errores de controlador de los rechazos SSL/TLS del servidor.

La versión 0.10.2 descarga automáticamente los adaptadores JDBC que falten para PostgreSQL, SQL Server, Oracle, SQLite y H2. Los guarda en el perfil local de Windows, comprueba una firma SHA-256 fijada por NexoDB y muestra el progreso antes de volver a intentar la conexión. MySQL y MariaDB continúan incluidos en la aplicación.

La versión 0.10.1 corrigió las copias interrumpidas por fechas cero: transfiere estos valores como texto compatible, ejecuta los nuevos lotes dentro de una transacción, permite reanudar copiando únicamente claves primarias faltantes y verifica conteos, faltantes y filas adicionales.

Cliente de bases de datos de escritorio, portable y extensible mediante JDBC.

## Funciones incluidas

- Perfiles de conexión para PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, SQLite y JDBC genérico.
- MySQL Connector/J oficial incluido y detectado automáticamente.
- MariaDB Connector/J incluido, con cambio automático cuando el servidor solicita `auth_gssapi_client`.
- Selector explícito entre usuario/contraseña y autenticación integrada de Windows (GSSAPI/SSPI) para MySQL/MariaDB.
- Descarga automática bajo demanda de los controladores JDBC registrados, con verificación SHA-256; también admite archivos `.jar` seleccionados manualmente.
- Explorador de catálogos, esquemas, tablas, vistas y columnas.
- Editor SQL con ejecución de la selección o sentencia actual.
- Cancelación de consultas, límite de filas, transacciones manuales, `commit` y `rollback`.
- Resultados ordenables y exportación CSV.
- Historial local de consultas.
- Actualizaciones seguras desde la propia aplicación con verificación SHA-256.
- Icono propio para la ventana y el ejecutable de Windows.
- Interfaz moderna FlatLaf con iconos vectoriales y los logotipos reales de cada motor.
- Las contraseñas nunca se guardan.

## Ejecutar durante el desarrollo

Requiere JDK 17 o superior.

```powershell
.\run.ps1
```

El script compila el programa y lo abre.

## Crear versión portable para Windows

```powershell
.\package.ps1
```

El resultado queda en `dist\NexoDB Studio\NexoDB Studio.exe` e incluye un runtime Java reducido.

La distribución limita el heap a 256 MB y usa renderizado GDI por software para evitar problemas de controladores gráficos en Windows.

## Actualizaciones

Para el usuario final, **Ayuda → Buscar actualizaciones** consulta el canal incorporado al generar el ejecutable. **Canal de actualizaciones (administrador)** solamente se usa para probar o reemplazar ese canal.

El actualizador descarga el ZIP en segundo plano, compara su SHA-256 con el manifiesto y solo entonces permite instalarlo. La instalación se realiza después de cerrar NexoDB y la aplicación vuelve a abrirse.

`package.ps1` genera `dist\update-manifest.json` e incorpora el canal en el ejecutable cuando recibe la dirección pública:

```powershell
.\package.ps1 -UpdateBaseUrl 'https://tu-servidor.example/nexodb'
```

Publica juntos el manifiesto y el ZIP producido. Sin una dirección pública donde alojarlos no existe un canal remoto que la aplicación pueda consultar.

## Conectarse

1. Abre **Nueva conexión**.
2. Elige el motor, completa host, puerto, base de datos y usuario.
3. Para MySQL y MariaDB no necesitas seleccionar ningún archivo: sus controladores oficiales ya están incluidos.
4. Para PostgreSQL, SQL Server, Oracle, SQLite, H2 y MongoDB, NexoDB descarga automáticamente el controlador oficial si falta. También puedes comprobarlo antes con **Verificar / descargar driver**.
5. Escribe la contraseña y pulsa **Probar** o **Guardar y conectar**.

En MySQL puedes dejar **Base / servicio / archivo** vacío. NexoDB conectará al servidor y mostrará en **Objetos** todas las bases, tablas y vistas que el usuario tenga permiso de consultar.

En PostgreSQL también puedes dejar **Base / servicio / archivo** vacío: NexoDB consulta las bases disponibles y permite elegir una antes de cargar sus esquemas y objetos. Para consultar el catálogo utiliza inicialmente `postgres` y, si esa base no existe o no permite la conexión, prueba `template1`. Si el usuario no puede conectarse a ninguna de las dos, debes indicar una base existente a la que sí tenga acceso. PostgreSQL necesita una base inicial para consultar el servidor.

Los campos URL JDBC, clase y archivo del controlador están dentro de **Configuración avanzada**, colapsada por defecto. Para conexiones normales no necesitas modificarlos.

NexoDB usa exclusivamente el usuario y la contraseña escritos en el formulario. El usuario es obligatorio; la contraseña puede quedar vacía y se envía explícitamente como `password=""`. Nunca se sustituyen esos datos por la cuenta activa de Windows. El servidor debe tener una cuenta compatible con esas credenciales.

Si seleccionas **Windows integrada (GSSAPI/SSPI)**, la contraseña deja de enviarse y NexoDB usa deliberadamente las credenciales de la sesión actual de Windows. El campo Usuario continúa identificando la cuenta MariaDB configurada con GSSAPI, por ejemplo `root`.

Los perfiles guardan la configuración, pero no la contraseña. Para SQLite, `Host` puede quedar vacío y en `Base de datos` se indica la ruta del archivo.

## Atajos

- `Ctrl+Enter` o `F5`: ejecutar selección o sentencia actual.
- `Escape`: cancelar consulta.
- `Ctrl+S`: exportar el resultado visible a CSV.
- Doble clic en una tabla o vista: generar un `SELECT` limitado.

## Seguridad

Un controlador JDBC es código ejecutable. Usa únicamente controladores descargados de la página oficial del proveedor. Evita ejecutar modificaciones sin una copia de seguridad y usa el modo de solo lectura para conexiones de producción.

## Componente incluido

MySQL Connector/J 26.10.0, publicado por Oracle Corporation bajo GNU GPL v2 con Universal FOSS Exception v1.0. Artefacto `com.mysql:mysql-connector-j:26.10.0`, obtenido de Maven Central y verificado con SHA-256 `cef70caf532833c9ab8f41f74773ce22c08bf503fad215c9c2ee1e9db87e759e`.

MariaDB Connector/J 4.0.0, publicado bajo GNU LGPL 2.1. Artefacto `org.mariadb.jdbc:mariadb-java-client:4.0.0`, verificado con SHA-256 `bb1211dac59ef4c54b7a8ad4755a913d36d2e81b34ca1d4cb9ffa9d5633df01f`.

MariaDB Connector/J 3.5.11 y Waffle-JNA 3.6.0 se incluyen para autenticación integrada SSPI de Windows. El modo normal continúa usando Connector/J 4.0.0 y nunca consulta credenciales del sistema.

FlatLaf 3.7.2, publicado por FormDev bajo Apache License 2.0. Artefacto `com.formdev:flatlaf:3.7.2`, verificado con SHA-256 `917aff3963c88d797d0fd9b9ccbd70f7681c101df9d11c59e2bc7a3a6c0fabf4`.

FlatLaf Extras 3.7.2 y JSVG 2.1.0 renderizan los SVG. Los logotipos de motores proceden de Devicon (MIT) y del proyecto H2; las marcas pertenecen a sus respectivos propietarios y se usan únicamente para identificar el motor seleccionado.

