# Firma de las distribuciones

Los paquetes generados sin certificados son compilaciones sin firma. Cambiar el nombre, icono o compresión no los convierte en software de un editor verificado. Tampoco se debe desactivar SmartScreen, Gatekeeper o el antivirus para publicar el producto.

Una advertencia de editor desconocido no equivale a una detección de malware con un nombre de amenaza. Si existe una detección concreta, conserva su texto, el archivo afectado y su SHA-256: hay que investigar esa detección y, si corresponde, solicitar al fabricante una revisión de falso positivo. No hay una opción de compilación que garantice cero alertas de todos los antivirus.

## Windows

Se necesita un certificado de **firma de código** emitido por una autoridad de confianza, con la clave privada accesible en el almacén personal del usuario, y SignTool del Windows SDK. La huella del certificado es pública; las claves privadas y PIN no se guardan en el repositorio ni en los argumentos del script.

```powershell
$env:NEXODB_SIGN_CERT_THUMBPRINT = 'HUELLA_DEL_CERTIFICADO_INSTALADO'
.\installer.ps1 -RequireSigning -DownloadBaseUrl 'https://raw.githubusercontent.com/alexo8ec/IDE_Bases/main/dist'
```

El modo `-RequireSigning` falla antes de compilar si falta el certificado. Si se proporciona una huella, valida su uso, vigencia y cadena de confianza. Firma el lanzador de la aplicación antes de comprimir el portable; el EXE instalador se construye a partir de esa misma imagen, conservando el lanzador firmado, y se firma también. Verifica Authenticode y el sello de tiempo RFC 3161/SHA-256. Los hashes se calculan después de firmar.

```powershell
Get-AuthenticodeSignature 'dist/installer/AdisDB-0.11.3.exe'
Get-AuthenticodeSignature 'dist/0.11.3/AdisDB/AdisDB.exe'
```

Ambos deben indicar `Valid`. Sin una huella configurada, el empaquetado normal sigue sirviendo para pruebas locales, pero no se debe anunciar como una distribución firmada. Una firma autofirmada no resuelve la confianza de instalaciones públicas.

La firma permite identificar al editor y verificar integridad. **SmartScreen puede advertir incluso con una firma válida mientras se forma reputación.** Un certificado EV tampoco garantiza evitar esa advertencia. Consulta la documentación de [reputación de SmartScreen](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/smartscreen-reputation) y de [SignTool](https://learn.microsoft.com/en-us/windows/win32/seccrypto/signtool).

## macOS

Se necesita un Mac con JDK 17 de la arquitectura de destino, herramientas de Xcode, una identidad **Developer ID Application** válida en el llavero y un perfil de `notarytool` configurado localmente. Para un instalador PKG se necesita además Developer ID Installer; este proceso produce una aplicación ZIP y no utiliza PKG.

```sh
export NEXODB_MAC_SIGN_USER='Nombre del titular o equipo (TEAMID)'
export NEXODB_NOTARY_PROFILE='perfil-guardado-en-el-llavero'
bash tools/package-macos-signed.sh
```

El script construye un lanzador nativo con `jpackage`, firma la aplicación y sus componentes Java, verifica la firma, solicita notarización a Apple y exige el estado `Accepted`. Luego adjunta el ticket con `stapler`, lo valida y comprueba la aplicación con `spctl`. Solo después crea el ZIP final y su SHA-256 en `dist`. Se ejecuta por separado con un JDK Intel y otro ARM64; el nombre del ZIP refleja la arquitectura del JDK.

El ZIP firmado contiene la aplicación para arrastrarla a Aplicaciones. No distribuye un instalador de Terminal ni elimina cuarentena o altera las políticas de seguridad. Los paquetes ensamblados desde Windows con `tools/package-platforms.py` permanecen sin firma; no sustituyen este proceso de publicación.

Referencias: [Developer ID](https://developer.apple.com/developer-id/), [notarización de Apple](https://developer.apple.com/documentation/security/notarizing-macos-software-before-distribution) y [firma de paquetes Java](https://docs.oracle.com/en/java/javase/17/jpackage/support-application-features.html).

## Publicación

Conserva la misma identidad de editor entre versiones. Genera el manifiesto de actualización después de obtener los archivos definitivos firmados y notarizados, usando sus hashes finales. Una descarga íntegra por HTTPS y SHA-256 comprueba el archivo esperado; no reemplaza la firma del editor ni la notarización.

No se han firmado ni notarizado los paquetes 0.11.3 en este equipo: faltan certificados y no hay un entorno macOS disponible. Los scripts quedan preparados; la ejecución real debe completarse con esas identidades antes de anunciar que las alertas de confianza están resueltas.
