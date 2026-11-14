# NexoDB Studio para Android

## Descargar e instalar

La primera versión Android es **0.14.0**. El APK firmado se genera en
[`dist/NexoDB-Studio-0.14.0-android.apk`](../dist/NexoDB-Studio-0.14.0-android.apk).
No se necesita Java ni un ordenador encendido para utilizarla.

Después de publicar los archivos en la rama `main`, la descarga es:

https://raw.githubusercontent.com/alexo8ec/IDE_Bases/main/dist/NexoDB-Studio-0.14.0-android.apk

1. Descarga el APK en el teléfono y ábrelo desde Descargas.
2. Si Android lo solicita, permite instalar aplicaciones desde ese navegador o gestor de archivos.
3. Abre NexoDB Studio y activa tu licencia transferible mediante clave completa o archivo `.nexolicense`.
4. Crea una conexión con un servidor accesible desde el teléfono. La dirección `localhost` corresponde al teléfono, no al ordenador.

Requiere Android 8.0 o posterior y Android System WebView actualizado. El APK es universal para las arquitecturas del dispositivo, sin bibliotecas nativas específicas de CPU.

## Funciones de esta versión

- Conexiones directas MySQL, MariaDB y PostgreSQL, con contraseñas recordadas mediante Android Keystore.
- Editor SQL con pestañas, borradores, abrir/guardar `.sql`, historial, deshacer/rehacer y autocompletado de tablas y columnas.
- Consultas en solo lectura por defecto, límite de 1.000 filas y opción de quitarlo. Habilitar escritura requiere confirmación.
- Resultados con selección de filas, filtros, copia, exportación y edición cuando existe una clave primaria utilizable.
- Herramientas MySQL/MariaDB para estructura, índices, claves foráneas, diagramas, constructor de consultas JOIN, búsqueda, comparación de esquemas, respaldo/restauración SQL e importación CSV.
- Idiomas español/inglés, temas claro/oscuro/sistema y comprobación de actualizaciones desde Ajustes.

La versión móvil comparte componentes del motor SQL con escritorio, pero todavía no tiene todas sus herramientas: no incluye tareas programadas, administración de usuarios del servidor ni comparación/sincronización de datos. PostgreSQL ofrece conexión, consultas y exploración; los asistentes de estructura y respaldo de esta versión se limitan a MySQL/MariaDB.

## Actualizar

**Ajustes → Buscar actualizaciones** consulta:

https://raw.githubusercontent.com/alexo8ec/IDE_Bases/main/dist/android-update-manifest.json

La aplicación requiere una licencia vigente, solicita confirmación y verifica el SHA-256 antes de guardar la nueva APK. Abre después el archivo para instalarlo sobre la versión anterior y conservar los datos locales. Las versiones futuras deben conservar el identificador `com.nexodb.android`, la misma clave de firma y un `versionCode` mayor.

La versión y el código de actualización se obtienen de [`VERSION`](../VERSION); `major.minor.patch` se convierte en `major * 10000 + minor * 100 + patch`.

## Compilar

Con JDK 17, Gradle 8.11.1 y Android SDK 35:

```powershell
./android/build.ps1 -SdkPath 'C:/ruta/Android/Sdk'
```

El script genera el APK firmado, su archivo SHA-256 y el manifiesto directamente en `dist`. Los archivos intermedios se crean en la carpeta temporal para evitar bloqueos de OneDrive. Usa `-GradlePath` si Gradle no está en la caché conocida.

La clave de firma y su configuración se conservan exclusivamente en `.signing`, excluida de Git. Haz una copia privada de seguridad de ambas: no deben publicarse ni incluirse en el APK. La autoridad privada de licencias tampoco se distribuye. La firma de Android permite instalar futuras actualizaciones con la misma identidad; no constituye publicación en Google Play.
