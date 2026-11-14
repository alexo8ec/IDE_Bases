# NexoDB Studio para Android

## Descargar e instalar

La versión Android actual es **0.14.2**. El APK firmado se genera en
[`dist/NexoDB-Studio-0.14.2-android.apk`](../dist/NexoDB-Studio-0.14.2-android.apk).
No se necesita Java ni un ordenador encendido para utilizarla.

Después de publicar los archivos en la rama `main`, la descarga es:

https://raw.githubusercontent.com/alexo8ec/IDE_Bases/main/dist/NexoDB-Studio-0.14.2-android.apk

1. Descarga el APK en el teléfono y ábrelo desde Descargas.
2. Si Android lo solicita, permite instalar aplicaciones desde ese navegador o gestor de archivos.
3. Abre NexoDB Studio y activa tu licencia transferible mediante clave completa o archivo `.nexolicense`.
4. Crea una conexión con un servidor accesible desde el teléfono. La dirección `localhost` corresponde al teléfono, no al ordenador.

Requiere Android 8.0 o posterior y Android System WebView actualizado. El APK es universal para las arquitecturas del dispositivo, sin bibliotecas nativas específicas de CPU.

## Corrección 0.14.2

Corrige el error `Cannot read properties of null (reading forEach)` al ejecutar un SELECT. Las consultas con filas, las consultas vacías y las pestañas de resultados se comprueban en el WebView Android real mediante una prueba de interfaz con datos de prueba, sin consultar bases personales ni requerir una licencia administrativa.

## Funciones de esta versión

- Conexiones directas MySQL, MariaDB y PostgreSQL, con contraseñas recordadas mediante Android Keystore.
- Editor SQL con pestañas, borradores, abrir/guardar `.sql`, historial, deshacer/rehacer y autocompletado de tablas y columnas.
- Consultas en solo lectura por defecto, límite de 1.000 filas y opción de quitarlo. Habilitar escritura requiere confirmación.
- Resultados con selección de filas, filtros, copia, exportación y edición cuando existe una clave primaria utilizable.
- Herramientas MySQL/MariaDB para estructura, índices, claves foráneas, diagramas, constructor de consultas JOIN, búsqueda, comparación de esquemas, respaldo/restauración SQL e importación CSV.
- Idiomas español/inglés, temas claro/oscuro/sistema y comprobación de actualizaciones desde Ajustes.

La versión móvil comparte componentes del motor SQL con escritorio, pero todavía no tiene todas sus herramientas: no incluye tareas programadas, administración de usuarios del servidor ni comparación/sincronización de datos. PostgreSQL ofrece conexión, consultas y exploración; los asistentes de estructura y respaldo de esta versión se limitan a MySQL/MariaDB.

## Actualizar

**Preferencias → Buscar actualizaciones** consulta:

https://raw.githubusercontent.com/alexo8ec/IDE_Bases/main/dist/android-update-manifest.json

La aplicación requiere una licencia vigente. **Descargar e instalar** solicita confirmación, verifica el SHA-256, el identificador, la versión y la firma de la APK, y abre el instalador de Android. La primera vez, Android solicita permitir instalaciones desde NexoDB; al volver, se continúa con el instalador. Confirma la instalación para actualizar conservando licencia, perfiles y borradores. Desde versiones anteriores a 0.14.2, instala esta APK una vez mediante el archivo descargado; las siguientes actualizaciones ya podrán iniciar la instalación desde NexoDB. Las versiones futuras deben conservar el identificador `com.nexodb.android`, la misma clave de firma y un `versionCode` mayor.

La versión y el código de actualización se obtienen de [`android/VERSION`](VERSION); `major.minor.patch` se convierte en `major * 10000 + minor * 100 + patch`.

## Compilar

Con JDK 17, Gradle 8.11.1 y Android SDK 35:

```powershell
./android/build.ps1 -SdkPath 'C:/ruta/Android/Sdk'
```

El script genera el APK firmado, su archivo SHA-256 y el manifiesto directamente en `dist`. Los archivos intermedios se crean en la carpeta temporal para evitar bloqueos de OneDrive. Usa `-GradlePath` si Gradle no está en la caché conocida.

La clave de firma y su configuración se conservan exclusivamente en `.signing`, excluida de Git. Haz una copia privada de seguridad de ambas: no deben publicarse ni incluirse en el APK. La autoridad privada de licencias tampoco se distribuye. La firma de Android permite instalar futuras actualizaciones con la misma identidad; no constituye publicación en Google Play.

## Conexiones inactivas

Mientras la sesión permanece abierta se comprueba la conexión sin interferir con consultas, cursores o transacciones abiertas. Si un servidor o la red cierran una sesión sin transacción, se recupera usando las credenciales que ya están en memoria y se restauran base, esquema y modo de lectura. No se repite el SQL del usuario. Una transacción perdida requiere desconectar y volver a conectar expresamente. Si Android suspende la aplicación o no hay red, la conexión se valida al volver a utilizarla; no se requiere mantener una actividad en pantalla para conservar los perfiles.
