# Instrucciones de Vicky para AdisDB / IDE_Bases

Este archivo define cómo debe trabajar Vicky Agent cuando este repositorio está abierto en Visual Studio Code. Es una guía del proyecto: no amplía permisos, no autoriza acciones externas y no sustituye una orden explícita del usuario para publicar.

## Identidad y tecnología

- Producto visible: **AdisDB**, integrante de **AdisOne**.
- Nombre histórico del repositorio: `IDE_Bases`; paquete Java conservado por compatibilidad: `com.nexodb`.
- Escritorio: Java 17, Swing y FlatLaf.
- Android: proyecto Gradle independiente dentro de `android/`.
- Motores soportados: MySQL, MariaDB, PostgreSQL, SQL Server, SQLite, H2 y las capacidades que declare `DatabaseType`.
- No cambies identificadores históricos, rutas de datos, applicationId, claves públicas ni nombres compatibles sin una petición explícita y pruebas de migración.

## Forma obligatoria de trabajar

1. Entiende el objetivo y busca primero símbolos, textos visibles, pruebas y clases relacionadas.
2. Si un archivo es grande o aparece truncado, busca el método relevante y lee sus líneas exactas. No pidas al usuario rutas que ya estén en el índice.
3. Lee el bloque vigente antes de editarlo. Usa parches pequeños anclados por hash y líneas; no regeneres una clase completa para cambiar un método.
4. Conserva indentación, UTF-8, finales de línea y cambios locales que no pertenezcan a la tarea.
5. Aplica y guarda físicamente todos los archivos antes de compilar.
6. Revisa el diff únicamente de los archivos tocados.
7. Ejecuta las pruebas apropiadas. Si fallan, corrige el código, vuelve a guardarlo y repite primero la verificación que falló.
8. No anuncies que terminaste si solo existe un plan, hay archivos sin guardar, una prueba falla o no existe un diff verificable.

## Mapa del repositorio

- `src/com/nexodb/`: aplicación de escritorio.
- `src/com/nexodb/ui/`: ventanas, paneles Swing, editores y acciones de interfaz.
- `src/com/nexodb/db/`: sesiones JDBC, metadatos, SQL por motor y servicios de objetos.
- `src/com/nexodb/util/`: utilidades compartidas, SQL, seguridad y persistencia local.
- `src/com/nexodb/update/UpdateService.java`: versión que muestra y consulta el actualizador.
- `src/com/nexodb/reporting/`: motor y diseñador de reportes.
- `tests/`: pruebas Java ejecutadas por `test.ps1` o `test.sh`.
- `resources/`: recursos incluidos en el JAR; no coloques secretos.
- `android/`: aplicación Android, versión, Gradle y script de compilación.
- `tools/`: empaquetado, manifiestos, hashes y verificaciones de plataforma.
- `license-issuer/`: emisor de licencias; su material privado no forma parte de una entrega pública.
- `docs/`: documentación de distribución, firma y uso.

## Reglas de código

- Reutiliza clases y servicios existentes antes de crear otra implementación paralela.
- Mantén la lógica por motor centralizada en `DatabaseType` o en el servicio de base de datos correspondiente; no detectes motores únicamente por texto de la URL.
- Cuando el perfil y el servidor real difieran, usa el producto informado por JDBC para elegir sintaxis compatible.
- SQL debe conservar literales, comentarios, identificadores citados, delimitadores y semántica.
- Las acciones de escritura en bases de datos deben respetar solo lectura, confirmaciones, transacciones y comprobaciones de concurrencia.
- No muestres ni registres contraseñas JDBC, tokens, claves API, secretos de firma o contenido de `.signing`.
- Los cambios Swing deben ejecutarse en el EDT cuando corresponda y conservar tema, idioma, accesibilidad y atajos existentes.
- Añade o actualiza pruebas junto con cada corrección funcional siempre que exista un punto comprobable.

## Compilación y pruebas

En Windows, la verificación predeterminada después de modificar código es:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File test.ps1
```

Este script compila el código con `build.ps1`, compila las pruebas y ejecuta la suite disponible. No ejecutes `build.ps1` repetidamente si `test.ps1` ya lo hará.

Para compilar sin ejecutar la suite completa, solo cuando el usuario lo pida expresamente:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1
```

En Linux o macOS usa `bash test.sh` y `bash build.sh`. Las pruebas reales que crean servidores temporales se ejecutan únicamente cuando la tarea afecta esa función y se cumplen sus requisitos:

- `tools/test-query-edit.ps1`
- `tools/test-sql-transfer.ps1`
- `tools/test-stored-programs.ps1`

No uses perfiles de conexión ni bases del usuario para pruebas automatizadas.

## Versiones

- `VERSION` es la fuente de versión para Windows, Linux y macOS.
- `android/VERSION` es la fuente de versión para Android.
- `release.ps1` sincroniza `UpdateService.CURRENT_VERSION` y el encabezado de `README.md` con esos archivos.
- Para una entrega completa solicitada por el usuario, incrementa una sola vez el componente `patch` y, salvo que se ordene una plataforma específica, mantén `VERSION` y `android/VERSION` en el mismo valor.
- No incrementes versiones durante análisis, exploración ni reparaciones intermedias. Hazlo cuando el código y las pruebas estén listos para preparar la entrega.
- No edites manualmente hashes o tamaños: los scripts oficiales deben calcularlos desde los artefactos reales.

## Entrega oficial

No inicies una entrega hasta que las pruebas del código terminen correctamente. En Windows, una entrega completa sigue este orden:

1. Actualizar `VERSION` y `android/VERSION` al valor decidido.
2. Ejecutar `test.ps1`.
3. Ejecutar `release.ps1` para sincronizar versiones, generar Windows y Android y verificar sus manifiestos.
4. Ejecutar `python tools/package-platforms.py --offline` para Linux y macOS cuando los runtimes verificados ya estén en caché. Si faltan, informa antes de descargar; no ocultes una dependencia de red.
5. Ejecutar `python tools/verify-platforms.py`.
6. Ejecutar `python tools/finalize-release.py` para comprobar artefactos y generar `release-manifest.json` y `SHA256SUMS.txt`.
7. Ejecutar `git diff --check` y revisar `git status --short` y el diff final.

`release.ps1` genera y verifica Windows y Android, pero no reemplaza el empaquetado y la verificación de Linux/macOS. Los paquetes macOS ensamblados desde Windows permanecen sin firma Developer ID ni notarización; no afirmes lo contrario.

## Git y publicación

- Solo crea commit o ejecuta push si el usuario lo pide explícitamente.
- Antes de publicar, las pruebas, empaquetados, manifiestos y hashes solicitados deben estar correctos.
- Agrega rutas concretas; no incluyas archivos privados, temporales o ajenos a la tarea.
- Nunca uses force push, reset destructivo, clean, rebase automático ni sobrescribas cambios del usuario.
- Revisa que los artefactos y manifiestos publicados correspondan exactamente a la versión actual; el nombre del archivo por sí solo no es suficiente.

## Archivos privados y generados

- Nunca leas, envíes, modifiques ni publiques `.signing/`, `*.pfx`, `*.p12`, `*.p8`, `*.key`, `*.nxvault`, `*.nexolicense` o `.env.signing*`, salvo una operación de firma solicitada explícitamente que use los scripts existentes sin exponer su contenido.
- `build/`, `android/**/build/` y `android/.gradle/` son salidas generadas; no los edites como código fuente.
- `dist/` contiene artefactos. Solo los scripts oficiales deben reemplazar paquetes, manifiestos y hashes.
- `android/local.properties` es configuración local y no debe publicarse.

## Criterio de finalización

Al terminar informa únicamente resultados reales:

- archivos modificados;
- pruebas o compilaciones ejecutadas y su resultado;
- versión preparada, si corresponde;
- artefactos generados y verificados, si se solicitaron;
- comprobaciones visuales o de plataforma que sigan pendientes;
- commit y push solamente si realmente se completaron.

## Guías de trabajo por tarea

Usa este archivo como fuente principal de las reglas de AdisDB.
Antes de editar, carga las guías que correspondan al objetivo del usuario:

- `.vicky/skills/ui/SKILL.md`: ventanas, diálogos, cuadrículas, editores SQL,
  menús, atajos y cambios visuales.
- `.vicky/skills/sql-debugger/SKILL.md`: procedimientos almacenados,
  puntos de quiebre, variables, cancelación y resultados JDBC.
- `.vicky/skills/release/SKILL.md`: preparar una entrega, incrementar versión,
  generar instaladores o actualizar manifiestos y hashes.

Si la tarea combina varios objetivos, carga todas las guías pertinentes.
Por ejemplo, «centra los diálogos y genera la actualización» usa `ui` y
`release`. «Corrige el diálogo y compila para probarlo» usa `ui` y la
verificación normal del proyecto.

Si el usuario dice «ese cambio», «continúa» o «prepara la entrega»,
recupera el objetivo activo del mismo proyecto y conversación antes de actuar.

Los comandos, las versiones y el orden de entrega se rigen por las secciones
de este archivo y por los scripts vigentes del repositorio. Las guías detallan
cómo ejecutar y verificar cada tipo de tarea.

Si alguna guía no está disponible, aplica las reglas de este archivo y
continúa con las operaciones que puedas verificar.