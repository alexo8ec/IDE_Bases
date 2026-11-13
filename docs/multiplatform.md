# Distribución para Windows, Linux y macOS

NexoDB Studio 0.12.0 utiliza Java 17 y Swing/FlatLaf. La aplicación, los controladores JDBC, el editor, los temas y los idiomas comparten el mismo código en los tres sistemas.

## Archivos disponibles

Todos incluyen Java 17 para su sistema y arquitectura y corresponden a 0.12.0. Se generan directamente en `dist`, sin copias en otra carpeta de publicación. Los paquetes están sin firma: la distribución macOS firmada queda pendiente de Developer ID y notarización. Consulta la [guía de firma](signing.md).

| Sistema | Archivo | Instalación |
| --- | --- | --- |
| Windows x64 | [NexoDB Studio-0.12.0.exe](../dist/installer/NexoDB%20Studio-0.12.0.exe) | Ejecutar el instalador |
| Debian/Ubuntu x64 | [NexoDB-Studio-0.12.0-linux-x64.deb](../dist/NexoDB-Studio-0.12.0-linux-x64.deb) | `sudo apt install ./NexoDB-Studio-0.12.0-linux-x64.deb` |
| Debian/Ubuntu ARM64 | [NexoDB-Studio-0.12.0-linux-arm64.deb](../dist/NexoDB-Studio-0.12.0-linux-arm64.deb) | `sudo apt install ./NexoDB-Studio-0.12.0-linux-arm64.deb` |
| macOS Intel | [NexoDB-Studio-0.12.0-macos-x64.zip](../dist/NexoDB-Studio-0.12.0-macos-x64.zip) | Extraer el ZIP y abrir `Instalar NexoDB Studio.command` |
| macOS Apple Silicon | [NexoDB-Studio-0.12.0-macos-arm64.zip](../dist/NexoDB-Studio-0.12.0-macos-arm64.zip) | Extraer el ZIP y abrir `Instalar NexoDB Studio.command` |

En macOS también puedes arrastrar `NexoDB Studio.app` a Aplicaciones. El comando instala en `~/Applications`. La aplicación no tiene firma Developer ID ni notarización; macOS puede solicitar autorización para abrirla. Estos ZIP contienen una aplicación macOS y su instalador por usuario; no son DMG ni PKG.

El DEB crea la entrada del menú e instala en `/opt/nexodb-studio`. Está destinado a distribuciones que admiten paquetes Debian; para otras distribuciones se puede usar el paquete Java universal con Java 17 instalado, o generar un RPM en el sistema de destino.

Para reproducir los cuatro paquetes Unix desde la compilación existente:

```sh
python tools/package-platforms.py
python tools/verify-platforms.py
```

El generador descarga los runtimes nativos oficiales de Eclipse Temurin para cada arquitectura y verifica sus SHA-256 antes de incorporarlos. Conserva permisos ejecutables, enlaces simbólicos y licencias al construir el DEB o el ZIP. `--download-only` prepara la caché en `build/runtimes`; `--offline` genera los paquetes utilizando esa caché verificada.

## Paquete universal disponible

`python tools/package-universal.py` permite generar un ZIP universal a partir de la compilación existente y la versión indicada en `VERSION`. Incluye el JAR de producción y sus dependencias, los controladores MySQL/MariaDB, los lanzadores y un instalador para el usuario actual. **Requiere Java 17 o superior instalado en el equipo de destino.** Los paquetes de la tabla incluyen su propio runtime.

En Linux o macOS, extrae el ZIP y ejecuta dentro de la carpeta extraída:

```sh
sh install.sh
```

En Linux, instala en `$XDG_DATA_HOME/nexodb-studio`, o en `~/.local/share/nexodb-studio`, y crea una entrada del menú de aplicaciones. En macOS, crea `~/Applications/NexoDB Studio.app`. No solicita permisos de administrador. Para ejecutar sin instalar: `sh run.sh`. En Windows: `run.cmd` o el instalador EXE con Java incluido.

## Compilar y verificar desde el código fuente

Necesitas un JDK 17 o superior. En Linux/macOS:

```sh
bash build.sh
bash test.sh
bash run.sh
```

En Windows se mantienen los equivalentes PowerShell. Los scripts manejan rutas con espacios. No se incluye un runtime Windows en los paquetes de Linux/macOS.

## Instaladores nativos con Java incluido

Los DEB y ZIP macOS descritos arriba se ensamblan desde cualquier sistema con runtimes nativos verificados. Como alternativa, para generar lanzadores de `jpackage`, DMG, PKG o RPM, ejecuta `jpackage` en el sistema y arquitectura de destino.

| Equipo de compilación | Comando | Resultado |
| --- | --- | --- |
| Windows x64 | `./installer.ps1` | EXE y portable ZIP, con runtime |
| Linux x64 o ARM64 | `bash package.sh app-image` | Aplicación con runtime |
| Debian/Ubuntu | `bash package.sh deb` | DEB con runtime; requiere herramientas DEB y `fakeroot` |
| Distribución con herramientas RPM | `bash package.sh rpm` | RPM con runtime; requiere `rpmbuild` |
| macOS Intel o Apple Silicon | `bash package.sh dmg` | DMG con aplicación y runtime |
| macOS Intel o Apple Silicon | `bash package.sh pkg` | PKG con runtime |

La salida nativa Unix queda en `dist/native`. Utiliza un JDK de la misma arquitectura que el destino. Los paquetes macOS generados así no están firmados ni notarizados; la firma comercial requiere la identidad Developer ID del distribuidor.

## Preferencias

**Preferencias → Idioma y apariencia** ofrece Español, English y el idioma del sistema, además de Claro, Oscuro y Según el sistema. Ambos cambios se aplican al guardar, conservando las consultas y conexiones de la sesión actual. El cambio de idioma actualiza los textos de la interfaz y mantiene intactos el SQL, los datos de resultados y los nombres de objetos.

Las preferencias se guardan en `%APPDATA%/NexoDB Studio/settings.properties`, `~/Library/Application Support/NexoDB Studio/settings.properties` o `$XDG_CONFIG_HOME/nexodb-studio/settings.properties` (`~/.config` por defecto). El modo del sistema consulta Windows Personalize, AppleInterfaceStyle o el portal de escritorio Linux; en Linux también consulta GNOME/GTK. Si el escritorio no expone su preferencia, utiliza el tema claro y permite elegir oscuro manualmente. Los cambios del sistema se comprueban cada 15 segundos.

El guardado de contraseñas con DPAPI y la autenticación integrada GSSAPI/SSPI de Windows se ofrecen únicamente en Windows. En Linux/macOS, las conexiones mediante usuario/contraseña funcionan y la contraseña se pide al conectar. Nunca se guarda una contraseña sin cifrar como alternativa.

## Publicar actualizaciones

La descarga valida certificados en Java y en la conexión nativa. Si falla TLS en Java, reintenta TLS 1.2 y luego curl nativo. Mantiene HTTPS, evita redirecciones a HTTP y verifica SHA-256 antes de instalar. Un error HTTP 404 significa que falta un manifiesto público accesible; cambiar TLS no puede resolver esa ausencia.

El manifiesto admite paquetes por sistema y arquitectura. Genera el manifiesto **solo con archivos que hayas construido**:

```sh
python3 tools/create-update-manifest.py \
  --base-url https://servidor.example/nexodb/0.11.3 \
  --windows-x64 dist/NexoDB-Studio-0.11.3-win64.zip \
  --linux-x64 dist/NexoDB-Studio-0.11.3-linux-x64.deb \
  --linux-arm64 dist/NexoDB-Studio-0.11.3-linux-arm64.deb \
  --macos-x64 dist/NexoDB-Studio-0.11.3-macos-x64.zip \
  --macos-arm64 dist/NexoDB-Studio-0.11.3-macos-arm64.zip
```

Ajusta los nombres a los archivos reales; los nombres DEB/RPM dependen de `jpackage`. El generador admite también `--linux-arm64`, `--macos-x64` y `--windows-arm64`, sin suponer que existen. Conserva los campos Windows de primer nivel para clientes antiguos. Publica el manifiesto junto con los paquetes en un servidor HTTPS accesible sin iniciar sesión. Las instalaciones nuevas seleccionan exclusivamente su plataforma.

Windows mantiene el actualizador de reemplazo para el portable instalado. En Linux/macOS se descarga y verifica el paquete correspondiente, y se abre con el instalador del sistema sin cerrar la sesión de NexoDB. Cierra NexoDB antes de completar una instalación que reemplace sus archivos.

## Verificación de esta entrega

La compilación Java, las pruebas y las capturas de los temas se verifican en Windows. Las pruebas incluyen HTTPS local con certificados, errores HTTP, límites de tamaño, integridad de descargas, persistencia de preferencias, traducción del editor y cambios de tema sin modificar el SQL. Los scripts Unix se validan sintácticamente. Las pruebas de comparación verifican la selección por casillas en la ventana real, las respuestas antiguas descartadas, las bases recordadas por conexión, la lectura de 251 tablas con dos llamadas a metadatos y la lectura paralela de conexiones independientes. Las comprobaciones de los DEB y ZIP macOS validan SHA-256, arquitectura del binario nativo, permisos ejecutables, enlaces, versión de la aplicación y licencias. No se ejecutaron los paquetes en un equipo Linux o macOS; la instalación y la detección del tema deben comprobarse allí. El runtime reducido Windows incluye `jdk.crypto.ec` para habilitar los algoritmos EC usados por TLS; `tools/RuntimeTlsCheck.java` permite verificar ese proveedor y HTTPS directamente con el runtime distribuido.
