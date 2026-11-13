# NexoDB Studio

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

Canal estable de actualizaciones: `https://github.com/alexo8ec/IDE_Bases/releases/latest/download/update-manifest.json`. Los instaladores y manifiestos se publican como archivos de GitHub Releases sin guardar automatizaciones de GitHub Actions dentro del repositorio.

La versión 0.10.3 añadió en el formulario de conexión el botón **Verificar / descargar driver**. Comprueba el archivo, su firma y la clase JDBC sin abrir una conexión; si falta, lo descarga y muestra el progreso. También diferencia los errores de controlador de los rechazos SSL/TLS del servidor.

La versión 0.10.2 descarga automáticamente los adaptadores JDBC que falten para PostgreSQL, SQL Server, Oracle, SQLite y H2. Los guarda en el perfil local de Windows, comprueba una firma SHA-256 fijada por NexoDB y muestra el progreso antes de volver a intentar la conexión. MySQL y MariaDB continúan incluidos en la aplicación.

La versión 0.10.1 corrigió las copias interrumpidas por fechas cero: transfiere estos valores como texto compatible, ejecuta los nuevos lotes dentro de una transacción, permite reanudar copiando únicamente claves primarias faltantes y verifica conteos, faltantes y filas adicionales.

Cliente de bases de datos de escritorio, portable y extensible mediante JDBC.

## Funciones incluidas

- Perfiles de conexión para PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, SQLite y JDBC genérico.
- MySQL Connector/J oficial incluido y detectado automáticamente.
- MariaDB Connector/J incluido, con cambio automático cuando el servidor solicita `auth_gssapi_client`.
- Selector explícito entre usuario/contraseña y autenticación integrada de Windows (GSSAPI/SSPI) para MySQL/MariaDB.
- Carga dinámica de controladores JDBC adicionales desde archivos `.jar`.
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
3. Para MySQL no necesitas seleccionar ningún archivo: el controlador oficial ya está incluido.
4. Para otros motores, selecciona su controlador JDBC `.jar`.
5. Escribe la contraseña y pulsa **Probar** o **Guardar y conectar**.

En MySQL puedes dejar **Base / servicio / archivo** vacío. NexoDB conectará al servidor y mostrará en **Objetos** todas las bases, tablas y vistas que el usuario tenga permiso de consultar.

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

