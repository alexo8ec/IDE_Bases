AdisDB — Windows / Linux / macOS

Requiere Java 17 o superior. Este paquete contiene la aplicación y sus
dependencias; no incluye un runtime específico de Windows.

Linux / macOS:
  Extrae el ZIP y abre una terminal dentro de la carpeta AdisDB.
  sh install.sh
  Linux: abre AdisDB desde el menú de aplicaciones.
  macOS: abre ~/Applications/AdisDB.app.
  Para usar sin instalar: sh run.sh

Windows:
  Ejecuta run.cmd, o usa el instalador nativo que incluye Java.

Preferencias → Idioma y apariencia:
  Español / English / Según el sistema.
  Claro / Oscuro / Según el sistema.
El tema y el idioma cambian al guardar, sin cerrar tus consultas.

Las conexiones y consultas funcionan en los tres sistemas. El guardado
de contraseñas con Windows DPAPI y GSSAPI/SSPI de Windows solo se ofrecen
en Windows; en Linux/macOS se solicita la contraseña al conectar.

Los instaladores nativos DEB/RPM/DMG/PKG con Java incluido deben generarse
en cada sistema con package.sh. Estos paquetes no están firmados ni
notarizados para distribución comercial en macOS.

Java 17+ is required. Run sh install.sh on Linux/macOS, or run.cmd on
Windows. Preferences contains English and Spanish plus light/dark/system
appearance. Database credentials are never written to disk in plaintext.
