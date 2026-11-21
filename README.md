# AdisDB

La versión actual de escritorio es **0.18.68**. Incluye los siguientes cambios y sus paquetes para Windows, Linux y macOS. Android mantiene su versión independiente **0.18.32**.

## Código e historial por archivo al estilo GitHub — 0.18.68

- Cada SQL abierto muestra su ruta y los accesos **Código** e **Historial** en la parte superior del editor.
- **Historial** abre una línea de tiempo exclusiva del archivo y conserva el seguimiento a través de cambios de nombre.
- Al elegir un commit y un archivo se puede alternar entre **Cambios**, con líneas agregadas y eliminadas coloreadas, y **Archivo completo**, que muestra exactamente el contenido guardado en esa versión con números de línea.
- El contenido histórico es de solo lectura y puede copiarse; consultar versiones anteriores no modifica el archivo actual ni ejecuta SQL.

## Credenciales cifradas exclusivas por tarea — 0.18.67

- Cada respaldo, reporte o script programado conserva su propia copia de la contraseña después de verificarla. La credencial se cifra con Windows DPAPI para el usuario actual y queda separada del perfil de conexión.
- El ejecutor interno y el ejecutor externo cargan la credencial por identificador de tarea, por lo que reiniciar o cerrar AdisDB ya no convierte una contraseña válida en `using password: NO`.
- El formulario deja el guardado seguro activado obligatoriamente en Windows. La contraseña no se incluye en comandos, registros, configuración de la tarea ni archivos de respaldo.
- Al editar una tarea se reutiliza su copia cifrada; solo se vuelve a pedir la clave cuando falta, cambia o el servidor la rechaza. Al eliminar la tarea también se elimina su secreto cifrado.
- El diagnóstico distingue ahora entre una contraseña rechazada y la ausencia total de contraseña, indicando exactamente cómo reparar las tareas creadas con versiones anteriores.

## Diferencias Git e historial completo por archivo — 0.18.67

- Al seleccionar un commit, **Historial Git** muestra la lista real de archivos agregados, modificados, eliminados o renombrados, junto con sus totales de líneas añadidas y quitadas.
- El archivo seleccionado abre su parche completo con líneas nuevas en verde, eliminadas en rojo, bloques de cambio resaltados y encabezados diferenciados, tanto en modo claro como oscuro.
- El nuevo botón **Historial del archivo** filtra todas las revisiones del SQL abierto y sigue sus cambios de nombre, mientras **Ver historial** conserva la vista global de ramas y fusiones.
- La lectura es informativa y segura: no ejecuta SQL, no modifica archivos y no ofrece acciones destructivas automáticas sobre commits anteriores.

## Columnas constantes generadas correctamente — 0.18.66

- El asistente de IA entiende peticiones como “crea una columna llamada `ice` con contenido 1” y genera la expresión SQL en el orden correcto: `1 AS ice`.
- Las instrucciones del generador distinguen explícitamente el valor de su alias y evitan producir formas invertidas como `'ice' AS 1`.
- La propuesta continúa siendo editable y nunca se ejecuta automáticamente; el usuario puede revisar la constante, su tipo y el nombre de la columna antes de abrirla en una consulta.

## Historial Git empresarial y legible — 0.18.66

- **Proyecto SQL › Historial Git** muestra ahora una tabla visual con columnas para ramas, fecha relativa, autor, mensaje y referencias, en lugar de una lista compacta de dos líneas.
- El grafo conserva líneas de colores para ramas y fusiones; los commits de fusión llevan una marca `M` y las ramas o etiquetas aparecen como distintivos de color.
- Cada autor tiene un identificador visual, el hash corto queda debajo del mensaje y la fecha exacta permanece disponible como ayuda contextual.
- Al seleccionar un commit se conserva el panel inferior con sus archivos y estadísticas. No se añadió un rollback destructivo de un clic: las operaciones que reescriben historial continúan realizándose conscientemente desde la consola Git.

## Contraseña inequívoca en respaldos programados — 0.18.65

- El formulario identifica el campo como **Contraseña del usuario de la base** y muestra el usuario concreto de la conexión. Esa es la clave de MySQL o MariaDB; no es la contraseña de Windows ni el nombre de la base seleccionada.
- Si el servidor respondió `Access denied`, AdisDB deja de reutilizar silenciosamente la credencial cifrada anterior: exige introducir nuevamente la clave actual y la comprueba en una conexión independiente antes de aceptar la tarea.
- La opción de ejecución con AdisDB cerrado continúa cifrando la clave con el usuario actual de Windows, sin incluirla en comandos, registros ni archivos de respaldo.
- Después de verificar y guardar una tarea se elimina el error anterior de la columna **Último resultado** y se muestra que la configuración y la credencial quedaron verificadas, evitando confundir un fallo antiguo con el estado actual.

## Tareas programadas y credenciales verificadas — 0.18.64

- Al guardar o editar una tarea, AdisDB abre primero una conexión independiente con la credencial elegida. Una contraseña antigua ya no puede quedar programada mientras la conexión principal funciona con otra mantenida en memoria.
- Después de una conexión exitosa, la credencial de sesión se sincroniza con el ejecutor interno sin mostrarla ni escribirla en registros. Para trabajar con AdisDB cerrado continúa siendo obligatorio guardarla cifrada con Windows.
- Al cerrar con un respaldo activo, AdisDB distingue una cancelación del usuario de una interrupción por cierre. Con ejecutor externo activo, la tarea permanece habilitada y queda pendiente para reintentarse; sin ejecutor externo, la aplicación impide el cierre y explica cómo continuar.
- La ventana de resultado ofrece diagnósticos distintos para cancelación, entrega al ejecutor externo y rechazo real de credenciales.

## Formulario de conexión estable en macOS — 0.18.59

- Al escribir el nombre de una conexión y cambiar el motor, AdisDB conserva exactamente el nombre introducido por el usuario; las sugerencias como `MySQL local` solo cambian mientras el nombre siga siendo automático.
- Antes de procesar el cambio de motor se confirma cualquier composición de texto pendiente del sistema de entrada de macOS, evitando que el último fragmento escrito se pierda al abrir el selector.
- El formulario actualiza campos, puerto, controlador, TLS y URL sin ejecutar un `pack()` mientras el desplegable nativo está abierto. La ventana ya no se encoge, salta ni cambia de posición al elegir otro motor.
- La misma protección se aplica al seleccionar un archivo SQLite: el archivo solo propone un nombre cuando el usuario aún no ha escrito uno propio.

## Modelado avanzado y diccionario de datos — 0.18.58

- **Herramientas avanzadas › Diccionario de datos** reúne tablas, vistas, columnas, tipos, tamaños, valores predeterminados y comentarios del esquema seleccionado sin consultar filas ni definiciones SQL de las vistas.
- La carga inicial utiliza pocas lecturas de metadatos y funciona con esquemas grandes. Claves primarias, índices y relaciones se leen únicamente al seleccionar una tabla, evitando miles de llamadas innecesarias al servidor.
- El buscador encuentra coincidencias por tabla, columna, tipo y comentario. La ficha seleccionada muestra comentarios, nulabilidad, autoincremento, claves, columnas de índices y acciones `ON UPDATE` / `ON DELETE` de cada relación.
- Un doble clic abre la estructura real de la tabla y **Abrir en diagrama** continúa el modelado visual con el diseñador existente. Así, documentación, edición de estructura y diagrama permanecen conectados sin duplicar DDL.
- Las exportaciones Markdown y CSV completan los detalles en una conexión auxiliar, muestran progreso en el panel central de operaciones, se pueden cancelar y escriben el archivo de forma atómica. No exportan filas, contraseñas, parámetros ni SQL de las vistas.
- La interfaz funciona en modo oscuro e inglés, y el manual incorpora un capítulo paso a paso. Las pruebas reales verifican comentarios, PK, índices, claves foráneas, vistas, exportación completa y carga asíncrona.

## Paquetes Linux/macOS y actualización automática — 0.18.58

- El canal estable publica recursos distintos para Windows x64, Linux x64, Linux ARM64, macOS Intel y macOS Apple Silicon; cada instalación descarga únicamente el paquete de su sistema y arquitectura.
- Los paquetes Linux son `.deb` autocontenidos con Java 17; los paquetes macOS son aplicaciones `.app` autocontenidas con el runtime nativo correspondiente.
- Una copia instalada en `~/Applications/AdisDB.app` puede aplicar la actualización desde **Ayuda → Buscar actualizaciones**. AdisDB verifica HTTPS y SHA-256, espera el cierre, conserva temporalmente la aplicación anterior, instala la nueva y vuelve a abrirla.
- El reemplazo no toca `~/Library/Application Support/AdisDB`: conexiones, licencia, preferencias, borradores y espacios recuperables permanecen intactos.
- Si la aplicación está en una carpeta protegida o macOS no permite reemplazarla, AdisDB abre el ZIP verificado para completar la instalación manualmente. Los paquetes continúan sin firma Developer ID ni notarización mientras el distribuidor no disponga de esos certificados vigentes.

## Monitor histórico, deadlocks y replicación — 0.18.57

- El monitor del servidor incorpora pestañas separadas para **Sesiones**, **Bloqueos y deadlocks**, **Replicación** e **Historial**, sin reemplazar los controles existentes para filtrar, cancelar consultas o cerrar sesiones.
- MySQL y MariaDB enriquecen la lista de procesos con sus esperas reales mediante `performance_schema` y conservan un respaldo compatible con servidores anteriores. PostgreSQL, SQL Server y Oracle mantienen sus grafos nativos de bloqueo.
- AdisDB distingue una cadena de espera de un ciclo de deadlock, muestra las sesiones implicadas y recomienda revisar primero la transacción bloqueadora. Las acciones administrativas continúan requiriendo permiso del servidor y confirmación.
- El estado de replicación se consulta en modo informativo para MySQL/MariaDB, PostgreSQL, SQL Server y Oracle. Una cuenta sin privilegios no bloquea el resto del monitor: se muestra la limitación y las sesiones continúan disponibles.
- El historial conserva hasta siete días y mil muestras agregadas por perfil, sustituyendo refrescos consecutivos muy cercanos. Solo guarda cantidades, duración máxima, retraso y estado: nunca SQL, usuarios, identificadores de sesión, bases, parámetros, datos ni credenciales.
- Las nuevas pruebas verifican ciclos y falsas alarmas, consultas por motor, respaldo de esperas MySQL, aislamiento del historial por perfil y ausencia de campos sensibles.

## Calidad, enmascaramiento y datos de prueba — 0.18.56

- **Herramientas avanzadas › Calidad, enmascaramiento y datos de prueba** abre un espacio de trabajo con tres recorridos independientes y una selección explícita de tabla.
- **Calidad y perfil** calcula filas, valores `NULL`, cadenas vacías, valores distintos, mínimos, máximos y longitud máxima. Se ejecuta en una conexión auxiliar de solo lectura, tiene límite de tiempo por medición y no ocupa la conexión principal.
- **Enmascaramiento** ofrece reglas para conservar, establecer `NULL`, fijar un valor, generar un hash determinista y anonimizar correos, teléfonos o nombres. La vista previa compara el valor actual y el nuevo antes de permitir cualquier escritura.
- AdisDB exige una clave primaria completa, impide modificar claves primarias, columnas automáticas o generadas, valida nulabilidad, tipo y longitud, y actualiza únicamente las filas revisadas. Si una fila ya no coincide o aparece un error, revierte toda la operación.
- **Datos de prueba** genera una cuadrícula editable antes de insertar. Omite columnas autoincrementales, generadas y con valor predeterminado para que sea el servidor quien aplique sus reglas; la inserción completa se confirma o revierte como una sola transacción.
- Las operaciones de escritura se bloquean si existe una transacción manual pendiente, requieren confirmación y licencia/prueba vigente, y dejan una entrada segura en la auditoría sin guardar valores ni SQL.
- El manual incorporado documenta el flujo con ejemplos. Las pruebas reales cubren perfilado, anonimización determinista, recorte seguro, protección por clave, inserciones con identificadores únicos, reversión y la interfaz en modo oscuro.

## Ejecutor externo y respaldos verificables — 0.18.55

- El paquete de Windows incorpora `AdisDB Tasks.exe`, un ejecutor independiente y sin ventana que revisa las tareas vencidas sin abrir la interfaz principal.
- Desde **Tareas programadas** se puede activar o retirar su integración con el Programador de tareas de Windows y solicitar una comprobación inmediata. Comprueba cada minuto si llegó la hora exacta de una tarea, con la misma cuenta del usuario y sin colocar contraseñas en argumentos ni archivos de tareas.
- El agente y la aplicación comparten un bloqueo de proceso. Si coinciden en el mismo horario, solo uno ejecuta el trabajo; las modificaciones de tareas se rechazan de forma segura mientras otra instancia las procesa.
- Cada respaldo programado crea un manifiesto lateral con versión, motor, base, filas, tamaño y SHA-256. **Verificar respaldo** detecta archivos incompletos o modificados antes de restaurarlos.
- La tarea permite conservar de 1 a 1.000 versiones, o todas con 0. La limpieza utiliza un prefijo estable derivado del identificador de la tarea y nunca elimina archivos de otra tarea ni el respaldo recién creado.
- El ejecutor comparte el almacén de contraseñas DPAPI y aplica la misma prueba de 15 días o licencia activa. El manual explica activación, ejecución, retención y verificación.
- Las pruebas cubren alteración de archivos, retención, bloqueos entre procesos, argumentos seguros del sistema, ejecución sin interfaz, licencia compartida y controles en modo oscuro.

## Proyectos SQL, Git y control de cambios — 0.18.54

- **Archivo › Nuevo proyecto SQL** crea un espacio organizado para scripts, y **Abrir proyecto SQL** recupera un proyecto existente sin ejecutar su contenido.
- El explorador del proyecto permite crear carpetas y archivos SQL, renombrar, eliminar archivos locales y abrir un script en el editor principal. Los guardados son atómicos y avisan si otro programa modificó el archivo antes de sobrescribirlo.
- Cada proyecto puede inicializar su propio repositorio Git. Desde AdisDB se puede preparar un archivo o todos los cambios, retirar un archivo del área preparada, configurar una identidad local y crear commits descriptivos.
- Las pestañas **Cambios Git** e **Historial Git** muestran diferencias y hasta cien commits sin almacenar credenciales ni ejecutar SQL. Las rutas se validan para impedir salir de la carpeta del proyecto o modificar la carpeta interna de Git.
- El manual integrado incluye un capítulo completo con organización recomendada, flujo editar → guardar → revisar → preparar → commit y la diferencia entre historial Git y respaldo de datos.
- Se conservan la prueba de 15 días por instalación y la activación obligatoria después del vencimiento. Las pruebas automatizadas verifican licencia, rutas seguras, Git real, guardado, modo oscuro y apertura en el editor SQL.

## JOIN visual, recarga estable, prueba de 15 días y comparación por streaming — 0.18.53

- En el constructor visual, una línea JOIN se selecciona con clic, se edita con doble clic y ofrece con clic derecho el tipo compatible con el motor, cambio de columnas, eliminación de una condición o de todo el JOIN.
- **Actualizar** concilia los metadatos sin vaciar el lienzo: conserva tarjetas, posiciones, tamaños, colores, alias, columnas elegidas y relaciones que continúan siendo válidas.
- Cada instalación puede utilizarse durante 15 días desde el primer arranque sin licencia. El vencimiento persiste entre actualizaciones y controla retrocesos del reloj.
- La comparación avanzada fusiona filas ordenadas por clave a medida que llegan, puede cancelarse desde el panel de operaciones y evita conservar ambas tablas completas en memoria. Se verificó con 25.005 filas.

## Migración y comparación avanzada 1A — 0.18.52

- **Comparar datos** incorpora **Mapear columnas y claves** por tabla. Permite relacionar columnas con nombres diferentes, excluir campos y elegir una clave simple o compuesta aunque las claves primarias del origen y destino no tengan el mismo nombre.
- El mapeo propone coincidencias exactas y también nombres equivalentes que solo difieren en guiones o separadores. Antes de guardar valida que ninguna columna destino se utilice dos veces y que exista al menos una clave.
- La comparación usa los valores de las claves, no sus etiquetas, por lo que `id` en origen puede corresponder correctamente con `codigo` en destino. La vista previa genera `INSERT` y `UPDATE` usando los nombres reales del destino.
- No se ejecutan cambios automáticamente: el script continúa abriéndose en el editor o guardándose como archivo para revisión. Si no se configura un mapeo, se conserva íntegramente el comportamiento automático anterior.
- Después de una conexión exitosa, AdisDB cambia automáticamente de **Conexiones** a **Objetos** para mostrar inmediatamente las bases y objetos de la instancia activa. Una conexión cancelada o fallida no cambia la pestaña.
- El manual y los componentes comunes mejoran el contraste del modo oscuro: artículos, ejemplos, diagramas, formularios, tablas, listas y mensajes toman sus colores del tema activo y se actualizan al cambiar la apariencia.
- Se añadió una prueba real con dos bases aisladas, columnas renombradas, una fila faltante, una adicional y una modificada. El APK Android 0.18.25 se reconstruye y firma junto con esta entrega; la comparación avanzada de datos continúa siendo una herramienta de escritorio.

## Manual detallado y observabilidad mejorada — 0.18.51

- El manual integrado ahora documenta cada área de AdisDB con pasos, ejemplos, recomendaciones e ilustraciones: conexiones y SSH, editor SQL, edición de resultados, estructura de tablas, relaciones, comparación de esquemas, comparación de datos, diseñador de diagramas, reportes, usuarios, monitor, auditoría, recuperación e IA.
- La comparación de esquemas explica el recorrido conexión → base → objetos → diferencias → script DDL, y la comparación de datos explica selección de tablas, claves, límites, diferencias y sincronización segura.
- El monitor permite buscar y filtrar sesiones activas, inactivas, bloqueadas o lentas, ajustar el umbral y consultar un diagnóstico detallado sin exponer SQL por defecto.
- La auditoría local permite buscar, filtrar por resultado y exportar un CSV seguro que nunca contiene datos, SQL, parámetros ni contraseñas.
- El diagnóstico de lentitud es interactivo: al pulsarlo muestra hallazgos y acciones recomendadas. La recuperación identifica la conexión/base afectada y aclara qué contenido se restaura de forma segura.

## Monitor, recuperación, diagnóstico, auditoría y manual — 0.18.50

- El nuevo **Monitor de sesiones y bloqueos** muestra conexiones, consultas activas, sesiones inactivas, transacciones abiertas, bloqueadores, esperas y duración. El SQL permanece oculto hasta que el usuario decide mostrarlo. Puede cancelar consultas o cerrar sesiones únicamente tras confirmar y si el servidor concede permisos.
- MySQL/MariaDB, PostgreSQL, SQL Server, Oracle y H2 disponen de consultas de monitorización propias. Sin privilegios administrativos el panel conserva el modo informativo y explica la limitación, sin inmovilizar la interfaz.
- El monitor alerta sobre bloqueos prolongados y posibles límites alcanzados, se actualiza automáticamente con intervalo configurable y utiliza una conexión auxiliar para no ocupar la conexión principal.
- La **recuperación del espacio de trabajo** marca cierres inesperados, restaura los borradores SQL existentes y presenta las tareas que quedaron interrumpidas. No vuelve a ejecutar operaciones ni conserva filas, contraseñas o datos sensibles.
- Cada resultado incorpora un **diagnóstico automático de lentitud** que separa servidor/red, transferencia, metadatos e interfaz, y recomienda revisar planes, índices, bloqueos, filtros o tamaño de página según la evidencia observada.
- El planificador incorpora **límites adaptativos**: reduce la concurrencia después de errores, tiempos agotados o presión de memoria, y la recupera gradualmente tras operaciones rápidas. El límite efectivo se ve en el panel central.
- La nueva **auditoría local segura** registra acciones estructurales y administrativas, resultado, conexión y una huella irreversible. Nunca guarda SQL, parámetros, valores, direcciones JDBC, credenciales ni resultados.
- **Ayuda › Manual de uso** o F1 abre un manual sin conexión, con índice, buscador, instrucciones paso a paso, consejos e ilustraciones para conexiones, SSH, consultas, edición, filtros, exportación, estructura, reportes, permisos, monitor, rendimiento, IA, seguridad y actualizaciones.
- El explorador de objetos reconoce `TABLE`, `BASE TABLE`, `SYSTEM TABLE`, vistas y tablas temporales. Si un controlador filtra incorrectamente los metadatos, usa una lectura sin filtro y un respaldo de `information_schema` en MySQL/MariaDB, corrigiendo bases donde `SHOW TABLES` mostraba objetos pero el árbol aparecía vacío.

## Prioridades y protección por conexión — 0.18.49

- El planificador distingue operaciones **interactivas**, de **segundo plano** y **especulativas**. Consultas y recargas solicitadas por el usuario avanzan antes que conteos, exportaciones o precargas que todavía esperan turno.
- Al iniciar una operación interactiva se cancelan automáticamente las precargas obsoletas de la misma conexión. Las operaciones que ya terminaron o pertenecen a otra conexión no se alteran.
- Además del límite global, el panel permite definir de una a cuatro operaciones simultáneas por conexión. Una exportación de una instancia ya no puede ocupar todos los espacios disponibles ni frenar el trabajo en otra instancia.
- La tabla central muestra prioridad y posición real en la cola. Cuando se libera un espacio, el siguiente trabajo elegible se selecciona por prioridad y después por orden de llegada.
- Los límites global, por conexión y de tiempo se guardan juntos. Las pruebas cubren competencia entre conexiones, liberación de espacios, preferencia interactiva y cancelación de trabajo especulativo.

## Control de operaciones en segundo plano — 0.18.48

- El nuevo panel **Operaciones en segundo plano**, disponible desde la barra superior y Herramientas avanzadas, reúne consultas, conteos, exportaciones, reportes, recargas y precargas. Muestra conexión, estado, inicio, tiempo transcurrido, progreso y un detalle breve.
- Cada tarea se puede cancelar individualmente. Las lecturas seguras ofrecen **Reintentar**; las sentencias SQL no se repiten automáticamente para impedir que una escritura incierta se ejecute dos veces.
- El panel permite configurar entre una y ocho operaciones simultáneas y un tiempo máximo global. El exceso permanece en espera sin saturar el servidor y los límites se conservan para el siguiente inicio.
- La pérdida y recuperación de una conexión principal o auxiliar queda visible como una operación. AdisDB informa si se restableció o falló sin repetir sentencias ni ocultar la interrupción de una transacción.
- El historial conserva como máximo cien estados durante la sesión. No guarda SQL, parámetros, valores devueltos, rutas JDBC, contraseñas, tokens ni otros secretos.
- Las exportaciones incrementales y los reportes muestran su avance dentro del registro común; al cerrar la pestaña se cancelan también sus tareas y conexiones auxiliares.

## Precarga adaptativa y lecturas auxiliares — 0.18.47

- La página siguiente y la anterior se precargan discretamente cuando la consulta, la memoria y la latencia lo permiten. Volver o avanzar suele ser inmediato sin conservar resultados ilimitados en memoria.
- La caché se ajusta entre dos y cuatro páginas según la memoria disponible. Cambiar filtros, orden, tamaño de página o consulta cancela cualquier precarga obsoleta y descarta sus cursores.
- Conteos, exportaciones completas, recargas para Reportería y lectura de metadatos usan conexiones auxiliares de solo lectura. La conexión principal queda libre para continuar trabajando y las credenciales permanecen dentro de la conexión segura existente.
- Las acciones solicitadas por el usuario tienen prioridad: navegar, recargar, contar manualmente o exportar cancela el trabajo especulativo. El grupo auxiliar está limitado a dos conexiones reutilizables y aplica esperas máximas para no dejar tareas detenidas indefinidamente.
- Los encabezados muestran juntos, en su extremo derecho, el icono de orden ascendente/descendente y el icono de filtro. El área del título continúa seleccionando la columna sin modificar el orden.
- Al cerrar una consulta se cancelan sus conteos, exportaciones, recargas y precargas. Las pruebas verifican reutilización y límite del grupo de conexiones, cancelación por prioridad, recuperación de páginas, filtros, edición, JOIN y exportación incremental.

## Conteo asíncrono y navegación avanzada — 0.18.46

- El total exacto se calcula en segundo plano sin inmovilizar la interfaz. Mientras no se conoce, el pie muestra una estimación conservadora de **al menos** las filas alcanzadas.
- Los `SELECT` sencillos se cuentan automáticamente. Las consultas con agrupaciones, valores distintos, uniones o límites solicitan confirmación antes del conteo costoso, aplican un límite de 20 segundos y permiten cancelar la operación.
- La navegación incorpora primera y última página, además de un campo para escribir directamente el número de página. Los controles se actualizan con el total, el rango y el número real de páginas.
- La última página usa el orden inverso de una clave estable y recupera únicamente las filas finales necesarias. Si la consulta no posee una clave u orden seguro, conserva automáticamente la ruta compatible por `OFFSET`.
- Los saltos directos a páginas muy profundas advierten cuando todavía no existe un cursor eficiente. Cambiar filtros, orden o datos invalida el total anterior para impedir que se muestre información desactualizada.
- Las pruebas verifican conteos exactos con parámetros, cancelación disponible, navegación inicial/final/directa, última página por clave inversa y los respaldos seguros entre dialectos.

## Paginación por clave para páginas profundas — 0.18.45

- Cuando el resultado posee una clave primaria y un orden seguro, las páginas siguientes se solicitan desde los valores de la última fila recibida. La base ya no necesita recorrer todas las filas anteriores con un `OFFSET` cada vez mayor.
- El orden elegido en los encabezados se conserva y la clave primaria se agrega como desempate estable. Se admiten claves compuestas y combinaciones ascendentes y descendentes sin duplicar ni omitir registros con valores repetidos.
- AdisDB conserva cursores ligeros por página para regresar a páginas anteriores, mientras la caché de datos continúa limitada a las tres páginas recientes.
- El pie del resultado muestra **clave** cuando la página se obtuvo mediante este método. Los filtros y el orden global aplicados en el servidor generan un cursor nuevo desde la primera página.
- Las consultas con `LIMIT`, `TOP`, `OFFSET` o `FETCH` propios, expresiones de orden complejas, columnas ambiguas, claves nulas o motores sin soporte conservan automáticamente la paginación anterior. Nunca se modifica una consulta cuando no puede demostrarse que el cambio sea seguro.
- Las pruebas verifican cursores parametrizados, filtros preparados, claves ascendentes y descendentes, cambios concurrentes entre páginas, navegación visual y retorno seguro a `OFFSET`.

## Exportación incremental y prefijos SQL — 0.18.44

- Las exportaciones de resultados virtuales vuelven a ejecutar de forma segura el `SELECT` completo y escriben cada fila directamente al archivo. Ya no se limitan a la página visible ni necesitan cargar todo el resultado en memoria.
- CSV, TSV, TXT, JSON, XML, HTML, SQL y Excel se generan incrementalmente. Se conservan los filtros, el orden y los parámetros preparados activos; la barra de estado informa las filas procesadas y la operación se puede cancelar.
- El archivo se construye primero en una ubicación temporal y solo reemplaza al destino cuando termina correctamente. Si la base, el disco o el usuario interrumpen el proceso, se elimina el temporal y se conserva intacto cualquier archivo anterior.
- La exportación directa de una tabla usa `0 = todas` como valor predeterminado y también transmite las filas por bloques, por lo que admite volúmenes grandes sin agotar la memoria.
- Cuando una consulta usa un alias, el autocompletado inserta automáticamente el prefijo, por ejemplo `v.id_empresa`. Si participan varias tablas, la ventana identifica cada sugerencia con su tabla y alias para elegir la columna correcta.
- Las pruebas verifican 25.005 filas más allá de la primera página, parámetros preparados, progreso, recuperación atómica ante errores y selección de alias entre varias tablas.

## Filtros y orden global en resultados virtuales — 0.18.43

- El buscador general y los filtros de cada columna ya no trabajan solamente con la página visible: se ejecutan en la base de datos sobre el resultado completo y regresan a la primera página con las coincidencias reales.
- El orden ascendente o descendente de una o varias columnas también se aplica al conjunto completo antes de paginar. Al cambiar de página se conserva exactamente el filtro y el orden activos.
- Los valores del usuario se envían como parámetros preparados, sin concatenarlos al SQL. Se escapan correctamente los comodines de `LIKE` y se respetan filtros numéricos, texto, `NULL`, vacío y combinaciones `AND`/`OR`.
- Una espera breve de 350 ms agrupa lo escrito antes de consultar, evita solicitudes por cada tecla e invalida la caché anterior solamente cuando cambia la vista.
- Las consultas pequeñas conservan el filtrado local inmediato. Las formas que no se pueden envolver con seguridad mantienen la ruta compatible y editable sin alterar el SQL original.
- Las pruebas verifican búsquedas fuera de la página cargada, rangos numéricos, orden global, parámetros hostiles y compatibilidad con los dialectos soportados.

## Carga virtual de resultados — 0.18.42

- Las consultas `SELECT` grandes ya no conservan todas sus filas en memoria cuando se desactiva **Limitar filas**: comienzan con una página de 1.000 registros y solicitan las siguientes bajo demanda.
- Al llegar al final de la cuadrícula con la rueda, las flechas, `Page Down` o `End`, se carga automáticamente la página siguiente. Las flechas compactas de navegación continúan disponibles.
- Se mantienen en una caché acotada únicamente las tres páginas más recientes. Volver a una de ellas es inmediato y **Recargar** siempre invalida la caché para consultar datos actuales.
- MySQL, MariaDB, PostgreSQL, SQLite y H2 usan `LIMIT/OFFSET` en el servidor. SQL Server y Oracle usan `OFFSET/FETCH` cuando la consulta posee un `ORDER BY` seguro; los casos con límites explícitos conservan una ruta compatible.
- La paginación conserva la identidad editable, filtros, referencias y seguridad de la consulta. Las pruebas verifican 2.505 filas sin saltos ni duplicados, la carga automática y la reutilización de páginas.

## Actualización que conserva la versión instalada — 0.18.41

- La versión mostrada por la aplicación y utilizada para buscar actualizaciones ya no está escrita manualmente en el código: la compilación la genera siempre desde el archivo único `VERSION`.
- El JAR incorpora esa versión como recurso y como atributo de su manifiesto. La compilación se detiene si `VERSION` no es válido y las pruebas abren el JAR final para comprobar que sus tres valores coincidan.
- Se corrige el ciclo de 0.18.40 que instalaba los archivos nuevos pero continuaba identificándose como 0.18.39, por lo que volvía a ofrecer la misma actualización al reiniciar.

## Diagnóstico de rendimiento por etapas — 0.18.40

- Cada consulta mide por separado el tiempo de respuesta del servidor/red, la lectura y conversión de filas, la preparación de metadatos para edición segura y la construcción de la cuadrícula.
- El pie del resultado muestra el desglose junto al tiempo total; al mantener el puntero sobre él explica cada etapa. Así se puede distinguir una consulta lenta de una transferencia grande o una interfaz que tarda en dibujar los datos.
- La medición se conserva al paginar, recargar, editar, exportar o reutilizar el resultado en Reportería, sin alterar el tiempo total compatible con el historial existente.
- Esta instrumentación es la base de la siguiente mejora de rendimiento: carga virtual de resultados y diagnóstico de operaciones activas.

## Inserción visual, relaciones directas y actividad fluida — 0.18.39

- Los resultados editables muestran siempre una última fila de inserción, incluso cuando la consulta no devuelve registros. También se incorporó un botón **Nueva fila** visible; la fila auxiliar no cuenta como dato ni se copia o exporta.
- Las columnas `AUTO_INCREMENT` se muestran como `(AUTO)`, no admiten escritura manual y se omiten del `INSERT`, de modo que el servidor genera identificadores únicos. Las tablas nuevas crean su `id` autonumérico de forma predeterminada cuando el motor lo admite.
- Los `ENUM` de MySQL/MariaDB se editan mediante una lista desplegable con sus valores reales y la opción de conservar el valor predeterminado.
- Las claves foráneas abren directamente la vista de la tabla relacionada: con valor muestran únicamente la coincidencia exacta; con `NULL` o vacío muestran los registros disponibles para consultar o seleccionar.
- Las barras de actividad de consultas y operaciones generales usan animación basada en tiempo para mantener un movimiento continuo. MySQL reutiliza además sentencias preparadas y estado de sesión para reducir trabajo repetido.

## Actualización real de datos en Reportería — 0.18.38

- **Actualizar datos** vuelve a ejecutar únicamente el `SELECT` que originó el resultado elegido; ya no se limita a reconstruir la lista con la copia anterior.
- La cuadrícula original y la vista previa del reporte reciben las filas nuevas inmediatamente, respetando la página y el límite activos.
- El diseño se conserva: orientación, títulos, anchos, agrupaciones, totales y objetos no se reinician al actualizar.
- Si la consulta fue cerrada, cambió de base o tiene cambios pendientes, se muestra el motivo y no se reemplaza silenciosamente el reporte.

## Conexiones mediante túnel SSH — 0.18.37

- La ventana de conexión incorpora una pestaña **SSH** para llegar a bases de datos que no exponen su puerto directamente.
- Admite autenticación SSH por contraseña o clave privada con frase de clave opcional.
- El puerto local puede asignarse automáticamente; también incluye tiempo de espera, keep-alive y verificación mediante `known_hosts`.
- La contraseña o frase SSH se cifra por separado con Windows DPAPI y nunca se guarda dentro del perfil de conexión.
- El túnel se abre antes de JDBC, conserva SSL/TLS de la base de datos y se cierra automáticamente al desconectar.

## Impresión horizontal fiel a la vista previa — 0.18.36

- La orientación del reporte se envía ahora a la impresora como una propiedad real del trabajo de impresión.
- Al elegir **Horizontal**, tanto una impresora física como **Microsoft Print to PDF** reciben una página A4 apaisada, sin girar el contenido dentro de una hoja vertical.
- La orientación elegida en el cuadro de impresión también se respeta al generar el trabajo final.

## Lenguaje natural y edición de ENUM/SET — 0.18.35

- En **Asistente de IA → Generar consulta** se escribe directamente lo que se necesita en lenguaje natural. Antes de enviar, AdisDB toma la conexión y la base seleccionadas en ese momento, lee únicamente metadatos y prepara un contexto limitado con tablas, vistas, columnas, claves e índices relevantes para que el proveedor genere SQL del dialecto correcto.
- Si se cambia de conexión, base o descripción, el contexto anterior se invalida y se actualiza automáticamente al generar. La pestaña de SQL queda como referencia opcional; en los modos de optimización continúa siendo el SQL de entrada. La propuesta nunca se ejecuta automáticamente: primero se muestra para revisión y puede abrirse en una consulta nueva.
- Al modificar una columna `ENUM` o `SET` en una tabla MySQL/MariaDB existente, el botón **Modificar**, el doble clic y el menú contextual abren una ventana para agregar, reemplazar, eliminar, subir y bajar valores. AdisDB interpreta correctamente comas, comillas y barras existentes, conserva el borrador y genera la definición segura solo al guardar la estructura.

## Diseñador visual de tablas — 0.18.34

- **Nueva tabla** crea la tabla directamente y, cuando finaliza, la incorpora al diagrama y actualiza el explorador de objetos y el autocompletado.
- La cuadrícula muestra tipos de datos propios de MySQL/MariaDB, PostgreSQL, SQL Server, Oracle, SQLite y H2. Incluye clave primaria, nulos, autoincremento, `UNSIGNED`, valor predeterminado y collation.
- Un panel lateral permite configurar las propiedades avanzadas admitidas por el motor: `ZEROFILL`, binario, `ON UPDATE CURRENT_TIMESTAMP`, charset, comentario, columna generada, expresión e invisibilidad. Para `ENUM` y `SET` incluye un editor para agregar, reemplazar, eliminar y reordenar valores sin escribir manualmente las comillas. La vista previa SQL se actualiza antes de crear.
- Si el servidor rechaza el SQL, la ventana permanece abierta y conserva nombres, columnas y propiedades para corregir el problema sin rehacer el diseño.
- Al actualizar el diagrama, las relaciones existentes permanecen visibles mientras se releen. La modificación de claves foráneas MySQL/MariaDB se realiza en dos pasos y restaura automáticamente la relación anterior si la nueva definición falla.

## Navegación estable y recarga individual — 0.18.33

- Al modificar una celda y desplazarse con las flechas, el guardado automático actualiza solamente la fila anterior y conserva la fila y columna alcanzadas. La selección ya no regresa al inicio del resultado, incluso con filtros u ordenamiento activos.
- El botón **Recargar** vuelve a ejecutar exclusivamente la sentencia vinculada a la pestaña de resultado actual. Funciona también con consultas agregadas como `SUM(...)` que permanecen en solo lectura, sin ejecutar las otras sentencias del editor; las operaciones que puedan escribir datos no se repiten automáticamente.

## Portapapeles y edición de consultas parciales — 0.18.32

- `Ctrl+C` copia el valor exacto de una celda o la selección tabular, y `Ctrl+V` pega directamente en la celda activa solamente cuando el resultado está en modo de edición y la columna admite cambios. El menú contextual también ofrece **Pegar en la celda**.
- En un `SELECT precio, extendido FROM tabla`, AdisDB obtiene internamente la clave primaria que falte, la mantiene oculta y habilita la edición sin agregar columnas visibles. La clave se usa para actualizar exactamente la fila seleccionada y no se aplica esta ampliación a consultas internas ni a formas ambiguas, vistas o tablas sin clave primaria.

## Conexiones resilientes y consultas más rápidas — 0.18.31

- Una conexión remota que vence por tiempo realiza un segundo intento breve dentro del tiempo total que antes consumía un solo intento. Si tampoco responde, AdisDB indica el servidor y puerto y aclara que todavía no se validaron las credenciales, para distinguir red, VPN, firewall o puerto de un problema de usuario y contraseña.
- MySQL y MariaDB leen los resultados por bloques de 500 filas; MySQL activa el cursor del controlador y reutiliza metadatos de resultados. La información de tabla, columnas y clave primaria necesaria para editar resultados queda en caché por conexión y se invalida automáticamente al cambiar la estructura.
- El menú compacto de cada encabezado ofrece **Orden ascendente**, **Orden descendente** y **Quitar ordenación** con iconos y marca de la dirección activa. La flecha permanece visible junto al icono de filtro y el orden se aplica inmediatamente a las filas cargadas.

## Filtros compactos por columna — 0.18.30

El embudo de cada encabezado abre una ventana compacta al estilo dbForge, con buscador y valores disponibles, además de `NULL`, `No NULL`, limpiar y filtro personalizado. Al volver a abrirla conserva el valor buscado y las condiciones aplicadas; si se pasa al filtro personalizado, el texto escrito aparece ya colocado en la primera condición.

El formulario grande de condiciones personalizadas se centra respecto a la ventana principal de AdisDB, aunque la cuadrícula se encuentre en la parte inferior o en un panel lateral. El menú de clic derecho continúa disponible.

## Administración global de usuarios MySQL/MariaDB — 0.18.29

Cuando el usuario seleccionado ya tiene privilegios globales, el editor abre directamente **Administrador / todo el servidor (`*.*`)**. Así, los permisos dejan de parecer bloqueados por herencia al inspeccionar una base concreta. Desde una base también se puede saltar al ámbito global que concede las casillas heredadas.

La acción **Conceder administración total** aplica el perfil nativo `ALL PRIVILEGES ON *.* WITH GRANT OPTION`. Esto diferencia a una cuenta con acceso total de una cuenta que además puede conceder o retirar permisos a otros usuarios. Los privilegios globales y `GRANT OPTION` también se pueden desmarcar de forma individual para retirar administración sin ocultar los cambios que se enviarán al servidor.

Los encabezados de las cuadrículas muestran un icono de embudo permanente. Al pulsarlo se abre el filtro de esa columna; el embudo cambia de color cuando hay un filtro activo. Se conservan el desplegable existente y todas las acciones mediante clic derecho.

## Permisos, filtros, contenido XML e impresión de reportes — 0.18.28

La administración de usuarios MySQL/MariaDB lee los `GRANT` efectivos y presenta marcados los permisos actuales por base, patrones como `` `%`.* `` y ámbito global `*.*`. Distingue permisos explícitos de heredados, administración de cuentas mediante `CREATE USER` y delegación mediante `GRANT OPTION`; al aceptar genera únicamente los `GRANT` y `REVOKE` necesarios.

Cada columna de resultados incorpora un filtro desplegable con valores distintos, `NULL`, `No NULL` y dos condiciones personalizadas combinables con `AND`/`OR`. Los filtros de distintas columnas se combinan entre sí sin modificar ni volver a ejecutar la consulta.

El editor de textos largos conserva exactamente el contenido y los saltos de línea originales. El XML solo cambia de formato cuando se pulsa **Formatear XML**. En Reportería, la orientación se aplica al instante, la impresión usa exactamente el diseño paginado mostrado, lo adapta proporcionalmente al área imprimible y reutiliza el cálculo existente para responder más rápido sin bloquear la ventana.

## Edición segura de consultas JOIN y guardado por fila — 0.18.6

Los resultados de consultas con `JOIN` ya no quedan forzosamente en solo lectura. El selector de modo muestra cada tabla física que puede modificarse; al elegir una, AdisDB habilita exclusivamente sus columnas y usa su clave primaria completa para identificar la fila. Las tablas sin clave primaria proyectada no se ofrecen, las columnas calculadas siguen protegidas y las escrituras continúan usando parámetros y control de cambios concurrentes.

Al terminar una edición y pasar a otra fila, la fila anterior se guarda automáticamente. Si el servidor rechaza el cambio, el valor permanece marcado como pendiente para corregirlo o descartarlo. **Guardar** continúa disponible para cambios masivos, inserciones y eliminaciones.

## Editores completos de valores y espacio de trabajo compacto — 0.18.5

Al hacer doble clic sobre una columna `TEXT`, `LONGTEXT`, `CLOB`, XML, JSON o un texto largo se abre una ventana amplia con todo el contenido, contador de caracteres, copia completa y carga/guardado UTF-8. Abrir y aceptar sin editar conserva exactamente el valor y sus saltos de línea originales. Los XML muestran **Formatear XML** como acción explícita y segura; nunca se compactan ni reformatean automáticamente. En modo de edición el valor queda como cambio pendiente y solo se envía a la base al pulsar **Guardar**; en solo lectura el diálogo permite inspeccionar y copiar sin modificar datos. Los CLOB ya no se recortan a 10.000 caracteres.

Las columnas `DATE`, `TIME`, `DATETIME` y `TIMESTAMP` abren un selector con calendario, hora hasta segundos, acción **Ahora** y opción para establecer `NULL`. Los tipos JDBC se conservan desde el resultado para que los editores funcionen también al visualizar datos sin habilitar cambios.

La paginación usa flechas compactas en lugar de los textos Anterior/Siguiente. Se retiró la barra inferior global de sentencias y versión para entregar más altura a las cuadrículas; el progreso de operaciones permanece visible en la barra superior y los estados propios continúan dentro de cada pestaña.

## Vicky integrada en el asistente SQL — 0.18.3

El Asistente de IA incorpora **Vicky** como proveedor predeterminado. Se conecta a su agente de bases de datos en el VPS privado mediante Tailscale, conserva el token cifrado con DPAPI y deja el modelo administrado por Vicky. Solo comparte la solicitud y las pestañas visibles de SQL, esquema y plan; no envía credenciales JDBC, filas de resultados ni ejecuta automáticamente el SQL propuesto.

Al abrir el asistente desde una conexión, carga en segundo plano columnas, tipos, claves primarias, índices ordenados y relaciones de las tablas referenciadas en el SQL (hasta 20 tablas y 100.000 caracteres de esquema). **Actualizar contexto de tablas** vuelve a leer esos metadatos sin ejecutar la consulta. Los fallos de permisos o metadatos no disponibles quedan visibles. **Solicitud a enviar** permite inspeccionar el contexto y el JSON sin la clave API; **Diagnóstico** conserva el envío y el resultado HTTP, su duración y los avisos del proveedor. Vicky recibe también el SQL, motor, esquema y plan dentro de la instrucción completa para evitar que se pierdan si el agente solo procesa ese campo. Una respuesta sin SQL se identifica expresamente y no se anuncia como propuesta lista.

## Corrección del autocompletado SQL — 0.18.2

Aceptar una sugerencia con clic, Enter o Tab reemplaza el prefijo escrito sin duplicarlo: `c.ide` se convierte en `c.identificacion`. Se corrige el desfase de posiciones en consultas con saltos de línea de Windows, tanto para columnas como para tablas y JOIN. El cursor queda al final de la inserción, se conserva el SQL posterior y deshacer/rehacer agrupa el reemplazo en un solo paso.

**Esc** cierra el autocompletado y descarta el temporizador y las respuestas pendientes, para que el cuadro no reaparezca al terminar una consulta de metadatos. Sin autocompletado pendiente, Esc conserva su función de cancelar la consulta.

La actualización se ofrece desde el canal HTTPS existente al abrir AdisDB o mediante **Ayuda → Buscar actualizaciones**. Conserva las licencias, conexiones, preferencias y borradores existentes. La autoridad privada del generador de licencias no forma parte de la distribución.

## Corrección del depurador MySQL — 0.18.1

El icono conserva la base de datos original como símbolo principal y añade una A pequeña como distintivo de AdisDB, en escritorio y Android.

Corrige el error `Streaming result set ... is still active` (SQLSTATE S1000) al avanzar o continuar un SP. MySQL Connector/J intentaba restaurar `net_write_timeout` al cerrar un resultado mientras el resultado siguiente del CALL ya estaba abierto. La conexión dedicada de ejecución usa `netTimeoutForStreamingResults=0` para desactivar esa modificación automática del controlador; el perfil guardado y la conexión del editor no se alteran. El streaming sigue activo: no se descartan resultados ni se vuelve a ejecutar el SELECT.

La regresión se reproduce y valida contra una instancia MariaDB aislada utilizando ambos controladores reales incluidos: MySQL Connector/J y MariaDB Connector/J. Se comprueban pausas, pasos, resultados disponibles antes de terminar el SP, varios SELECT, NULL/decimales, bloques con LEAVE, conjuntos vacíos, límite de 10.000 filas, parámetros OUT y cancelación. Ejecuta `tools/test-stored-programs.ps1 -MysqlDriver -WithUi` para el controlador MySQL y el mismo comando sin `-MysqlDriver` para MariaDB.

## AdisDB · Suite AdisOne — 0.18.0

El producto se llama **AdisDB**, integrante de la suite **AdisOne**, con nueva marca en ventanas, menús, icono, reportes e instaladores. Los paquetes se distribuyen como AdisDB para Windows, Linux, macOS y Android. Se conservan el formato de licencias, la autoridad pública, los almacenes de conexiones/contraseñas/preferencias/borradores y los identificadores de actualización existentes. No necesitas volver a activar ni volver a registrar tus conexiones por este cambio de nombre.

El ZIP de Windows incluye lanzadores de compatibilidad para que el actualizador anterior de NexoDB Studio pueda copiar el paquete y reiniciar. El nuevo instalador utiliza AdisDB y mantiene la familia de actualización de Windows; Android conserva applicationId y firma. Los nombres internos `com.nexodb`, las carpetas de datos heredadas y las extensiones de archivos existentes siguen siendo identificadores de compatibilidad. El generador privado de licencias existente continúa funcionando.

## Inspección de variables, scripts SQLyog y errores — 0.17.0

**F10** ejecuta una instrucción completa y pausa antes de la siguiente; un SELECT que ocupa varias líneas se ejecuta como una unidad. Los puntos colocados en una línea de continuación se fijan al inicio de la instrucción. Durante la pausa, pasa el cursor por una variable del código para ver su valor real, tipo, ámbito y línea del contexto; doble clic abre su visualizador. No se ejecuta SQL al pasar el cursor. El historial conserva las últimas 100 pausas con línea, tiempo transcurrido (incluye tiempo detenido) y variables que cambiaron; sus contextos se identifican como históricos.

Las variables JSON con objetos o arrays se muestran como tablas mediante doble clic o **Visualizar variable…**. Las vistas previas truncadas no se interpretan como JSON completo. Los SELECT del SP se reciben por streaming y aparecen en **Resultados en tiempo real** antes de finalizar la llamada; no se repite el SELECT. También sirven para inspeccionar tablas temporales de la misma sesión. MySQL/MariaDB no ofrecen variables de tabla como SQL Server: para este motor se visualizan JSON y los resultados SELECT reales, sin simular tipos de otro motor. Límite de 10.000 filas por conjunto, 100 conjuntos y 4096 caracteres por variable.

En **Procedimientos → Crear ejemplo de depuración…** se abre un borrador revisable. Guarda y aplica, luego abre **Depurar procedimiento…** sobre el nuevo objeto. El ejemplo carga `v_base` mediante SELECT INTO, copia a `v_copia` en la siguiente instrucción, calcula un total, llena una tabla temporal y una variable JSON, devuelve filas y cambia el total. No modifica tablas permanentes; requiere CREATE TEMPORARY TABLES. También está disponible en [examples/sp_debug_variables.sql](examples/sp_debug_variables.sql).

El editor admite y muestra scripts SQLyog con **DELIMITER, USE, DROP … IF EXISTS y CREATE**. Acepta también una definición CREATE sin envoltura. Procesa las directivas en el cliente y valida la definición antes de reemplazar el objeto; el DROP visible no se ejecuta antes de la validación. El script debe corresponder al objeto y base abiertos. Las definiciones nuevas sin nombre coincidente y los scripts con operaciones adicionales se rechazan con detalle. Los separadores finales `$$` se retiran antes de enviar JDBC; se normaliza incluso un bloque pegado como `CREATE ... END$$` sin la directiva inicial, sin alterar literales ni comentarios.

Los errores de consultas y de creación/modificación de SP marcan la línea en rojo y muestran explicación en español, sugerencia, código/SQLSTATE y mensaje original, con **Ir a la línea del error**. Las posiciones se trasladan al documento completo, incluyendo selecciones, varias sentencias y la envoltura DELIMITER. Si el motor no proporciona posición exacta se identifica la instrucción asociada; no se afirma que esa posición sea exacta. El depurador registra el último punto y sus variables también al continuar sin puntos de quiebre para contextualizar un fallo. Editar el texto limpia las marcas anteriores.

Las cuadrículas normales y del depurador incluyen **Exportar…** en Excel (.xlsx), TXT tabulado, CSV, SQL INSERT, TSV, JSON, XML y HTML. El menú permite copiar celda, todas las filas visibles o las filas seleccionadas. La exportación conserva los datos cargados de la página/conjunto actual; no ejecuta otra consulta ni exporta páginas sin cargar.

## Objetos y depuración de procedimientos — 0.16.0

Para **MySQL/MariaDB**, el explorador ofrece Crear (**F4**), Modificar (**F6**) y Eliminar (**Supr**) en Vistas, Procedimientos, Funciones, Triggers y Eventos. Al seleccionar solo el grupo, las acciones que requieren un objeto quedan desactivadas. Las vistas y los eventos ofrecen Renombrar (**F2**); renombrar triggers permanece deshabilitado porque el motor no lo admite directamente. Hay exportación de definición SQL y, para vistas, Ver datos/Exportar datos. Los eventos nuevos se crean deshabilitados.

El editor de objetos envía el cuerpo completo como una sentencia JDBC, sin dividirlo en los puntos y coma internos. **Guardar y aplicar / Ctrl+S** muestra la definición a confirmar. Comprueba cambios concurrentes, protege perfiles de solo lectura y transacciones abiertas, conserva SQL_MODE y restaura el contexto de conexión. Antes de reemplazar rutinas valida la propuesta en una copia; para triggers valida sobre una tabla auxiliar sin datos y conserva el orden respecto a otros triggers si no se cambia su tabla/evento. MySQL requiere DROP/CREATE para cambiar cuerpos de rutinas/triggers: no es atómico y los permisos de rutina pueden necesitar reaplicarse. Ante un error posterior al DROP se intenta restaurar la definición anterior y se informa si la restauración falla.

**Procedimiento → Depurar procedimiento…** abre una sesión real en dos conexiones independientes. Completa parámetros IN/INOUT y variables de sesión, marca puntos junto a las líneas ejecutables o con **F9**, y pulsa **Iniciar depuración**. La primera pausa es automática. **F5** continúa hasta otro punto y **F10** ejecuta la instrucción pausada y se detiene en el siguiente punto instrumentado. El visor resalta la posición y muestra parámetros, variables locales visibles por ámbito, NULL y variables de sesión. **Shift+F5** detiene la conexión de ejecución; cerrar una sesión activa exige detenerla primero.

El backend crea una copia temporal del SP, una rutina de pausa y dos tablas MEMORY con nombres únicos `__nxdbg_…`; elimina esos auxiliares al terminar o cancelar, conservando el procedimiento original. Se ejecuta SQL real: los cambios ya confirmados no se revierten por pulsar Detener. Se requieren permisos CREATE/DROP para las rutinas/tablas auxiliares, acceso al original y permiso para cancelar la conexión propia. Si se pierde el controlador, la rutina aborta la pausa tras unos 20 segundos; un cierre forzoso puede dejar metadatos auxiliares que habrá que retirar cuando ya no exista la sesión.

**Alcance comprobado:** cuerpos BEGIN/END, parámetros IN/OUT/INOUT, variables locales y de sesión, bloques anidados con nombres repetidos, IF/CASE, WHILE/LOOP/REPEAT y cursores con `CONTINUE HANDLER FOR NOT FOUND SET …`. La pausa ocurre antes de cada instrucción instrumentada; los encabezados WHILE/LOOP se interceptan al entrar y los puntos del cuerpo se repiten en cada iteración. DECLARE no es un punto ejecutable. Las llamadas internas se ejecutan completas, sin entrar en sus variables. Para evitar modificar su semántica se rechazan antes de crear auxiliares los handlers generales, SQL dinámico PREPARE/EXECUTE, ROW_COUNT/FOUND_ROWS, diagnósticos y el modo ORACLE de MariaDB. Las variables largas muestran una vista previa señalada de 4096 caracteres; los resultados se muestran hasta 10.000 filas por conjunto. No se implementa depuración nativa de SQL Server/Oracle ni conexión a un SP que ya esté ejecutándose.

Pruebas reales aisladas: `tools/test-stored-programs.ps1 -WithUi` valida los cinco tipos de DDL y menús, pausas reales, bucles, cursores, variables por ámbito, parámetros OUT/NULL, avance de una instrucción, cancelación antes de una escritura y limpieza de auxiliares. Usa MariaDB local exclusivamente en el puerto 43189 y comprueba el directorio de datos antes de modificar la base de prueba. Referencias del motor: [limitaciones de depuración nativa de MySQL](https://dev.mysql.com/doc/refman/8.0/en/stored-program-restrictions.html), [tablas MEMORY](https://dev.mysql.com/doc/refman/8.0/en/memory-storage-engine.html), [cancelación de conexiones](https://dev.mysql.com/doc/refman/8.0/en/kill.html).

## Reportería, IA y análisis de consultas (escritorio 0.15.0)

**Reportería → Diseñador de reportes** y el botón **Reporte…** del resultado abren el módulo independiente `com.nexodb.reporting`. Permite mover y redimensionar objetos en el encabezado, editar títulos, colores y tamaños, agregar indicadores y gráficos de barras, elegir/reordenar columnas, configurar hasta cinco niveles de agrupación y calcular conteo, suma, promedio, mínimo y máximo. Ofrece deshacer/rehacer, vista previa paginada, orientación vertical/horizontal, encabezados de detalle repetidos, continuación de grupos y pie con origen/número de página. Los gráficos muestran hasta ocho categorías principales, sin inventar un total para promedios o mínimos.

La orientación cambia inmediatamente al marcar **Horizontal**, sin depender de **Aplicar página**. La impresión reutiliza el mismo diseño ya paginado de la vista previa, lo centra dentro del área imprimible y conserva su proporción; no vuelve a calcular el reporte por cada página solicitada por Windows. El envío a la impresora se ejecuta en segundo plano y muestra su estado, por lo que la ventana no queda bloqueada. La exportación PDF también reutiliza la paginación visible cuando está actualizada.

Las plantillas **.nxreport** guardan el diseño y sus campos, sin SQL, contraseñas ni filas. Se reutilizan con resultados que tengan las mismas columnas en el mismo orden. **HTML y PDF** conservan el diseño visual; **CSV y Excel (.xlsx)** exportan los datos con encabezados de grupo, subtotales y total general en columnas. El PDF se renderiza a 144 dpi para conservar fuentes y Unicode; su texto es una imagen, no texto seleccionable. El módulo usa una copia de las filas cargadas de un resultado, incluida su página/límite actual, y sigue funcionando después de desconectar. Para un reporte completo, carga primero todas las filas necesarias desde el editor.

La compilación produce además `build/nexodb-reporting.jar`, reutilizable fuera de AdisDB con Java 17 y las bibliotecas estándar `java.base` y `java.desktop`. Su contrato es `ReportData` → `ReportDesign` → `ReportPlan`; `ReportDesignerPanel` recibe un proveedor de resultados y un adaptador CSV/XLSX. No depende de JDBC, licencias, preferencias ni clases de interfaz de AdisDB. `ReportStore` guarda plantillas y `ReportExporter` crea archivos mediante reemplazo atómico.

El pie de cada tabla muestra el **tiempo de consulta en milisegundos**, además del tiempo que se conserva en Mensajes, el rango cargado y los botones **Anterior / Siguiente**. El selector ofrece **100, 500, 1000 y Personalizado (1–10000)**. Para avanzar se vuelve a ejecutar un único SELECT/WITH de lectura y se recupera la ventana solicitada mediante JDBC, conservando LIMIT/TOP escritos en el SQL. Esta alternativa portátil recorre las filas anteriores: páginas lejanas pueden costar más que paginación nativa. Usa ORDER BY para un orden estable; cambios concurrentes pueden mover registros. El orden y filtro de la cuadrícula se aplican a la página cargada. CALL, EXEC y lotes con escrituras no se repiten para paginar. Si cambia la base/esquema, primero hay que volver al origen del resultado.

**Inteligencia artificial → Asistente de IA** genera SQL y propone optimizaciones de consultas o procedimientos. Permite utilizar **Vicky** en el VPS privado mediante Tailscale, **OpenAI Responses**, un endpoint compatible con Chat Completions u **Ollama local**. Al elegir Vicky se configura automáticamente su agente especializado de bases de datos; solo debes guardar el token privado. La solicitud, el SQL, el esquema y el plan son editables y visibles antes de enviar; no se incorporan filas, credenciales ni resultados automáticamente. La propuesta abre una consulta nueva en Solo lectura y nunca se ejecuta automáticamente. La clave puede permanecer en la sesión o guardarse con Windows DPAPI; la configuración ordinaria no guarda la clave. Para OpenAI se utiliza `store:false`; el transporte no sigue redirecciones. Referencias de los contratos: [Responses API](https://developers.openai.com/api/reference/resources/responses/methods/create), [compatibilidad de Ollama](https://docs.ollama.com/api/openai-compatibility).

**Consulta → Plan de ejecución (Ctrl+L)** muestra operadores conectados, propiedades, una tabla ordenable de costos/tiempos, el documento original y las recomendaciones de índices que entregue el motor. **Analizar con IA** prepara el SQL y el plan en el asistente, sin enviarlos todavía. Los costos estimados se distinguen de los milisegundos observados; los tiempos de operadores pueden incluir hijos o promedios por loop y no deben sumarse.

| Motor | Plan estimado | Plan real |
| --- | --- | --- |
| SQL Server | SHOWPLAN_XML, SELECT/WITH o EXEC | STATISTICS XML para SELECT/WITH, con confirmación |
| MySQL | EXPLAIN FORMAT=JSON | EXPLAIN ANALYZE FORMAT=TREE, según versión |
| MariaDB | EXPLAIN FORMAT=JSON | ANALYZE FORMAT=JSON, según versión |
| PostgreSQL | EXPLAIN FORMAT JSON | EXPLAIN ANALYZE, BUFFERS y JSON |
| SQLite | EXPLAIN QUERY PLAN, detalle básico | No disponible por esta interfaz |
| H2 | EXPLAIN, detalle textual | EXPLAIN ANALYZE, con las métricas que reporte H2 |

El gráfico muestra los tiempos disponibles, índice utilizado, forma de acceso y filas reales/leídas; la tabla permite ordenar las métricas y consultar sus propiedades. Los tiempos de MySQL/PostgreSQL se identifican como promedios por loop; SQL Server muestra el máximo de tiempo entre hilos y suma filas/CPU, sin sumar tiempos de operadores. Cuando el motor proporciona el tiempo total de la consulta, se muestra separado de la duración de obtención del plan. Las métricas ausentes permanecen sin reportar; los costos estimados no se convierten en milisegundos.

La pestaña **Diagnóstico de rendimiento** señala escaneos y ordenamientos de al menos 100.000 filas, lecturas de al menos 10.000 filas que superan 100 veces las filas devueltas y derrames a disco reportados. Son umbrales orientativos: distingue volúmenes reales de estimados, y un escaneo no demuestra por sí solo que falte un índice. Explica cuándo revisar filtros, JOIN, selectividad, orden de columnas y ORDER BY; las recomendaciones SQL del optimizador se mantienen separadas de estas observaciones.

El plan real ejecuta el SELECT y requiere confirmación. SQL Server necesita permisos SHOWPLAN y permisos para las instrucciones analizadas; el módulo restaura SHOWPLAN/STATISTICS al terminar, incluso ante error. Las recomendaciones de índices se abren como SQL para revisar, sin aplicarse. El plan Oracle y la depuración nativa de SQL Server/Oracle siguen fuera de esta integración; MySQL/MariaDB incorporan la copia instrumentada descrita en 0.16.0. Referencias: [XML de planes de SQL Server](https://learn.microsoft.com/en-us/sql/relational-databases/performance/save-an-execution-plan-in-xml-format), [MySQL EXPLAIN](https://dev.mysql.com/doc/refman/8.0/en/explain.html), [MariaDB ANALYZE JSON](https://mariadb.com/docs/server/reference/sql-statements/administrative-sql-statements/analyze-and-explain-statements/analyze-format-json), [PostgreSQL EXPLAIN](https://www.postgresql.org/docs/current/sql-explain.html).

Validación: H2 real para ejecución/paginación/EXPLAIN, servidor HTTP local para IA, fixtures XML/JSON para SQL Server/MySQL/PostgreSQL, exportaciones y pruebas del diseñador. La validación no sustituye probar permisos, versiones de servidor y modelos con la conexión/API real del usuario. Los paquetes de escritorio y su manifiesto corresponden a 0.18.0; Android mantiene su versión y manifiesto independientes.

Otros cambios de escritorio 0.15.0: **Tab** acepta el autocompletado y deja el cursor al final de la inserción, incluido el alias de tabla. **F5 / Ctrl+Enter** ejecutan únicamente el texto seleccionado; sin selección ejecutan todas las sentencias del editor, incluida la última aunque no termine en `;`. Cada resultado abre una pestaña independiente; también se recogen todos los resultados JDBC de `CALL` / `EXEC`, intercalados con sus conteos de filas afectadas. Si hay un error posterior se conservan los resultados anteriores. Los lotes con escrituras se detienen en el primer error y mantienen las confirmaciones y restricciones existentes.

**Exportar… / Ctrl+Shift+E** incorpora **Excel (.xlsx)** para el resultado activo, además de CSV y los demás formatos. El libro conserva encabezados, números, booleanos, texto Unicode e identificadores de más de 15 dígitos; el texto no se convierte en fórmulas. Incluye filtro y encabezado fijo. Respeta los límites de Excel y rechaza celdas o resultados demasiado grandes sin recortarlos silenciosamente. Exporta las filas cargadas, con el límite de consulta vigente. La depuración MySQL/MariaDB de 0.16.0 se describe arriba.

**Android 0.14.6** corrige el error `Cannot read properties of null (reading forEach)` al mostrar consultas SELECT. Incluye una prueba de interfaz en el WebView real para consultas con filas, resultados vacíos y varias pestañas. [Descarga e instalación](android/README.md). Android mantiene su versión en `android/VERSION` y su propio manifiesto de actualización.

La versión **0.14.6** incorporó [consulta y selección de registros relacionados por claves foráneas](docs/foreign-key-lookup.md), con búsqueda, paginación y asignación al borrador. Android incorpora la misma búsqueda y una pantalla de actualización con reintento de instalación. Añade formularios de conexión adaptados al motor, apertura de SQLite mediante selector de archivo y administración de usuarios para MySQL/MariaDB, PostgreSQL, SQL Server, Oracle y H2, con generación de contraseñas. Consulta la [guía de conexiones y usuarios](docs/users-and-connections.md). Mantiene las conexiones inactivas con comprobaciones periódicas y recuperación de sesiones sin transacciones, conservando base/esquema y modo de lectura. No repite consultas ni recupera silenciosamente una transacción interrumpida. Al aceptar tablas en FROM/JOIN, el editor propone alias breves como `ct_ventas AS v` y `ct_clientes AS c`, con sufijos para evitar repeticiones. Los JOIN por claves foráneas y el constructor visual utilizan estos identificadores, respetando los alias existentes. Añade la primera aplicación **Android** con conexión directa a bases de datos y mejora el escritorio con iconos de herramientas, sugerencias JOIN basadas en claves foráneas y diagramas con cuadrícula, colores, cierre de tarjetas y tamaño ajustable. Los instaladores y manifiestos se generan con la misma versión. Consulta las [descargas e instalación de Android](android/README.md) y la [distribución de escritorio](docs/multiplatform.md).

La versión **0.13.1** incorpora relaciones visuales: arrastra una columna hija sobre la columna referenciada, o usa clic derecho sobre una columna o una línea del diagrama. Puedes crear, modificar y eliminar claves foráneas, con columnas compuestas y acciones ON DELETE/ON UPDATE. El diálogo permite revisar el SQL, abrirlo en una consulta nueva o aplicarlo con confirmación Sí/No. Esc cancela el enlace. Actualizar conserva las tablas y sus posiciones en el diagrama.

Al hacer doble clic en una tabla MySQL/MariaDB se abre **Estructura**, con la pestaña **Datos** al lado. La ficha ofrece **Columnas, Índices, Claves foráneas, Avanzado y Vista previa SQL**, encabezado con motor/charset/collation, cuadrícula de columnas con casillas, tipos, longitud, predeterminados SQL, PK, NOT NULL, UNSIGNED, auto incremento, comentarios y expresiones generadas. **Editar estructura** se activa explícitamente. Los índices tienen un selector con casillas, orden de columnas y longitud de prefijo; las relaciones permiten elegir la base, tabla y parejas de columnas, incluso otra base del mismo servidor. Avanzado incluye comentario, auto incremento, formato de fila y opciones de almacenamiento.

Los cambios de esta ficha quedan en un borrador hasta **Guardar cambios** y confirmar el SQL. Revertir, cerrar con cambios pendientes y eliminar elementos del diseño explican su efecto. Se genera un único ALTER TABLE; se conserva el modo de solo lectura de la conexión, se bloquean transacciones abiertas y se comprueba que la estructura o el modo de escape SQL no hayan cambiado. Columnas e índices con atributos especiales no cubiertos por el editor requieren SQL manual para conservar esos atributos; los demás motores mantienen su visor anterior.

Se corrige `setMaxRows() out of range. 2147483647 > 5000000`: desmarcar **Limitar filas** utiliza el valor JDBC **0 (sin límite)**, manteniendo cualquier LIMIT escrito en el SQL. El doble clic de búsqueda abre la fila pulsada después de ordenar los resultados y espera a que termine la búsqueda. Validado con pruebas de arrastre y menús Swing, 1.205 filas sin límite, confirmaciones No/Sí y una instancia MariaDB aislada para índices, relaciones entre bases, columnas generadas, predeterminados y protección de cambios concurrentes.

La versión **0.13.0** permite abrir consultas desde archivos **.sql** (Ctrl+O), guardarlas (Ctrl+S) y guardarlas con otro nombre (Ctrl+Shift+S), en UTF-8. Reconoce UTF-8 con BOM y UTF-16 con BOM al abrir. Las pestañas muestran el nombre del archivo y un asterisco si hay cambios; conservan su asociación al archivo en el borrador de sesión. Si el archivo cambió externamente, pide utilizar Guardar como para evitar sobrescribir esa versión. **Ctrl+Z / Ctrl+Y** deshacen y rehacen por pestaña, sin contar los cambios de resaltado como ediciones; el autocompletado se deshace en un solo paso. La exportación de resultados pasa a **Ctrl+Shift+E**.

El botón **+** es compacto y abre un menú con consulta nueva, abrir SQL, constructor visual, diseñador de esquemas, búsqueda de datos e historial. **Ctrl+T** crea otra consulta. Las herramientas visuales para MySQL/MariaDB generan SELECT con columnas, alias, agregados, filtros, orden, DISTINCT, límites y JOIN; el diseñador muestra tablas y relaciones, permite arrastrarlas, guardar el diagrama y generar SQL de tablas o claves foráneas para revisarlo en otra consulta. El historial local conserva hasta 500 sentencias ejecutadas con su conexión, base, tiempo y resultado, sin volver a ejecutarlas automáticamente.

**Herramientas avanzadas** reúne comparación y sincronización revisable de datos/esquemas, importación externa CSV/TSV o copia desde otra conexión, perfil y enmascaramiento seguro, generación de datos de prueba, reportes y respaldos programados, administración de tareas y reconstrucción de autocompletado. La búsqueda acepta palabras, frases, OR y exclusiones con `-`; muestra hasta 100 coincidencias por tabla y puede abrir el SELECT correspondiente en el editor. El importador CSV/TSV para MySQL/MariaDB permite revisar y mapear columnas, conserva la diferencia entre NULL y cadena vacía, verifica que el archivo no haya cambiado y revierte el lote ante error o cancelación.

Las tareas programadas usan conexiones independientes, conservan su configuración por usuario y se pausan si cambian el SQL aprobado, la conexión o la licencia. Los reportes SELECT producen HTML y CSV; los respaldos MySQL/MariaDB producen SQL completo. Las contraseñas se conservan en memoria durante la sesión o se obtienen del almacén cifrado existente de Windows. No se guardan contraseñas en los archivos de tareas ni en el comando del sistema. La creación de tareas que escriben SQL exige autorización explícita y revisión del archivo. La ejecución automática usa esa autorización; no muestra una pregunta en cada horario.

En Windows, **Activar ejecutor externo** registra `AdisDB Tasks.exe` en el Programador de tareas para comprobar cada minuto si llegó la hora de un trabajo, con la misma cuenta del usuario, incluso si la ventana principal está cerrada. El ejecutor aplica la misma licencia/prueba y el mismo almacén DPAPI. Un bloqueo compartido evita ejecuciones duplicadas entre el agente y la aplicación. Cada respaldo programado crea un manifiesto con SHA-256, tamaño, base, motor, filas y versión; puede verificarse desde la interfaz. La retención elimina únicamente versiones antiguas pertenecientes a esa misma tarea y conserva el número configurado. ODBC/Access, Excel y envío SMTP continúan fuera de esta fase.

Se consultaron las referencias oficiales de SQLyog para [archivos SQL](https://webyog.com/article/37-file-management/), [atajos](https://sqlyogkb.webyog.com/article/41-keyboard-shortcuts), [constructor visual](https://webyog.com/article/58-the-sqlyog-query-builder/), [diseñador](https://sqlyogkb.webyog.com/article/166-the-sqlyog-schema-designer) y [agente de tareas](https://faq.webyog.com/content/27/114/en/introduction-to-the-_sqlyog-job-agent_-sja.html). Las herramientas de esta entrega tienen el alcance descrito arriba; no representan una reproducción completa de SQLyog Ultimate.

## Seguridad de acciones y límite de filas

Cada consulta nueva comienza en **Solo lectura**. Las instrucciones de escritura requieren desactivar ese modo de forma explícita y confirmar **Sí/No** con el servidor, la base y el SQL que se ejecutará; un error detiene el lote de escritura. Las conexiones protegidas como solo lectura en su perfil conservan esa restricción. La cuadrícula también inicia en **Solo lectura**: un doble clic no la desbloquea. Elige **Editar datos** y confirma para habilitar cambios; Guardar pide confirmación antes de escribir. Insertar, duplicar, asignar NULL, marcar eliminaciones y descartar cambios explican su efecto. La edición de estructura, administración de usuarios, copias, importación, restauración y exportación solicitan confirmación antes de aplicar su acción. Las lecturas SELECT, búsquedas y actualizaciones del visor no preguntan.

**Limitar filas** está activado inicialmente en **1.000**, tanto en el editor como en los datos de tabla. Desmarcarlo permite leer todas las filas devueltas por la sentencia, conservando cualquier LIMIT/TOP escrito expresamente en el SQL. La cuadrícula mantiene los resultados cargados en memoria; una lectura sin límite de tablas grandes puede necesitar más memoria.

## Licencias y generador privado

Cada instalación dispone de **15 días de prueba sin licencia**, contados desde el primer arranque. La fecha se conserva al actualizar la aplicación y se controla el retroceso del reloj. Al vencer, AdisDB solicita una licencia antes de abrir el espacio de trabajo. El usuario puede cargar un archivo **.nexolicense** o pegar la clave completa desde **Ayuda → Licencia de AdisDB…**. La validación utiliza **Ed25519** y una clave pública incorporada. El archivo firmado incluye titular, fecha de emisión, inicio y vencimiento opcional; las licencias son **transferibles entre equipos**, y una licencia ilimitada no vence. Su identificador tiene **49 caracteres**; para activar se necesita el archivo o la clave firmada completa, no solo ese identificador.

El generador administrativo se encuentra en `license-issuer/private-dist/NexoDB License Manager/` e incluye Java para Windows. Su ZIP privado es `license-issuer/private-dist/NexoDB-License-Manager-1.0.1-win64.zip`: **extrae todo el ZIP** y abre `NexoDB License Manager.exe` dentro de la carpeta extraída. Es una aplicación portátil; conserva juntas las carpetas `app` y `runtime` y el ejecutable. La ubicación de la autoridad se lee desde `app/issuer.properties`, compatible con rutas que contienen espacios y acentos. Si trasladas la autoridad, selecciónala con el botón **…**. La autoridad cifrada, la contraseña inicial y una licencia ilimitada para tu instalación están únicamente en **`.signing`**, con instrucciones en `.signing/LEEME.txt`. Desbloquea `issuer.nxvault` con la contraseña inicial de esa carpeta y cambia la contraseña de la autoridad. Puedes elegir días, meses, años o duración ilimitada y guardar el archivo para el cliente. Conserva una copia de seguridad privada de la autoridad; no la reemplaces al compilar otra versión.

**No publiques `.signing` ni la aplicación administrativa privada.** Git excluye esas carpetas y los archivos de licencia. El cliente y sus paquetes no contienen la clave privada, la contraseña inicial ni la licencia del administrador. Para reconstruir el generador: `./license-issuer/build.ps1 -Package`; para una autoridad totalmente nueva en una distribución nueva: `-InitializeAuthority` (no reemplaza una autoridad existente).

Para verificar el ejecutable con su Java incluido: `./tools/verify-issuer-launcher.ps1`. La comprobación abre y cierra la ventana bloqueada, comprueba la ubicación de la autoridad y los proveedores criptográficos, sin desbloquear ni modificar la autoridad. `python tools/verify-license-packages.py` revisa también los argumentos del lanzador y la separación entre archivos públicos y privados.

Las licencias se validan sin servidor de activación: no hay revocación remota ni control de instalaciones simultáneas. El vencimiento depende del reloj local, con detección local de retrocesos para licencias con vencimiento. Los paquetes públicos de GitHub siguen siendo descargables; la licencia controla el uso y el actualizador de la aplicación. Versiones anteriores a 0.13 no pueden restringir retroactivamente su descarga; al abrir 0.13 se solicita la activación. La firma de licencias no sustituye la firma Authenticode/Developer ID de los ejecutables, que sigue pendiente de los certificados del distribuidor.

La firma impide crear licencias compatibles sin la autoridad privada. No impide que una persona modifique o recompile el código público del cliente para eliminar sus comprobaciones. Para imponer revocación, concurrencia o controles independientes de un cliente modificado se necesita un servicio de licencias en el servidor.

La versión **0.12.0** abre los scripts generados por comparaciones y las plantillas SQL en **pestañas de consulta independientes**. El SQL anterior se conserva y no forma parte del nuevo script. **Consulta → Nueva consulta**, **Ctrl+N** o **+** crean otra pestaña; cada pestaña tiene su botón de cierre. El borrador local conserva todas las pestañas y sus cursores al reiniciar, sin ejecutar su contenido. Las consultas nuevas empiezan vacías; al recuperar un borrador antiguo se elimina únicamente la cabecera de ayuda predeterminada conocida, conservando el resto del SQL y los comentarios del usuario.

El resaltado, las mayúsculas automáticas y el autocompletado comparten un vocabulario ampliado de palabras SQL, tipos y funciones, incluyendo **YEAR** y **DECLARE**. Se complementa con las palabras y funciones publicadas por el controlador JDBC y, cuando está disponible, con `INFORMATION_SCHEMA.KEYWORDS` de MySQL/MariaDB. Las cadenas, los comentarios y los identificadores entre comillas no se convierten en palabras clave.

**Base de datos → Respaldo SQL…** e **Importar / restaurar SQL…** permiten respaldar y restaurar tablas o bases MySQL/MariaDB. También están en el menú contextual de la conexión, la base y la tabla. El respaldo ofrece casillas por objeto y categoría, estructura y datos / solo estructura / solo datos, y opciones avanzadas para CREATE DATABASE/USE, DROP, DEFINER, claves foráneas y una lectura consistente de InnoDB. Incluye todas las filas, sin aplicar el límite del visor. El archivo de destino se reemplaza únicamente al completar el respaldo.

Para restaurar, selecciona un archivo **SQL UTF-8**, la base de destino y **Revisar archivo**. Se muestra una vista previa y el número de sentencias que modifican o eliminan datos u objetos; **Importar SQL** solicita revisar el servidor y el destino antes de ejecutar. Por defecto redirige las referencias de la base de origen hacia el destino seleccionado y omite CREATE DATABASE/USE del archivo. La opción avanzada de respetar nombres permite archivos de varias bases. Un archivo que cambió después de revisarse debe revisarse otra vez. La importación usa una conexión independiente, admite DELIMITER y dumps nativos MySQL/MariaDB, se detiene al primer error y muestra su línea.

Las operaciones no cambian la transacción del editor. La importación requiere una conexión habilitada para escritura. Los cambios DDL de MySQL/MariaDB pueden confirmarse inmediatamente y permanecer tras un error o cancelación; el asistente lo indica antes de restaurar. Selecciona las dependencias necesarias de vistas y rutinas; los permisos determinan qué objetos se pueden respaldar. Una lectura consistente cubre InnoDB, requiere evitar cambios DDL durante el respaldo y no garantiza consistencia en otros motores. Los respaldos contienen los datos y objetos seleccionados, sin usuarios, privilegios del servidor ni configuración del servidor. Una sentencia individual admite hasta 32 MB; el respaldo genera INSERT por lotes y avisa si una fila supera el límite. Los paquetes macOS siguen pendientes de firma Developer ID y notarización; no se han eliminado esas advertencias.

La versión **0.11.4** activa la edición de resultados con doble clic o F2. Las casillas resaltan toda la fila, conservando su identidad al ordenar o filtrar. El clic derecho, la flecha de la celda o el botón ▾ abren un menú con inserción, duplicación, eliminación de filas marcadas, guardado/cancelación, NULL, cadena vacía, valor predeterminado, filtros, copia y exportación. Las operaciones permanecen pendientes hasta Guardar; el lote se aplica en una transacción y las eliminaciones se confirman antes de ejecutarse.

El editor guarda automáticamente todo el texto SQL, incluidas las sentencias no ejecutadas, y recupera su contenido y posición del cursor en el siguiente inicio. Guarda un borrador local por usuario junto a las preferencias; utiliza UTF-8 y reemplazo atómico. También guarda antes de cerrar o instalar una actualización. Recuperar el texto no abre conexiones ni ejecuta consultas.

Para publicar con identidad verificable, consulta la [guía de firma Windows y notarización macOS](docs/signing.md). Los paquetes generados sin certificados permanecen sin firma y pueden mostrar advertencias del sistema. La firma no garantiza eliminar inmediatamente las alertas de reputación de SmartScreen.

La versión **0.11.3** convierte los resultados de consultas en una cuadrícula con barra de acciones: **Editar, Guardar, Descartar, NULL y Recargar**. Las celdas modificadas se resaltan y se guardan juntas al pulsar Guardar; respeta el modo Auto-commit y las transacciones manuales. La edición requiere un SELECT de una tabla física con todas las columnas de su clave primaria. Un cambio concurrente provoca la reversión del lote, conservando los cambios pendientes para corregirlos o descartarlos.

Las casillas iniciales permiten marcar filas; las cabeceras seleccionan columnas completas y muestran una flecha de filtro en cada campo. La flecha ofrece los valores distintos cargados, `NULL`, `No NULL`, limpieza y un filtro personalizado con dos condiciones combinables mediante `AND`/`OR`: igualdad, diferencia, contiene, empieza/termina con, comparaciones, vacío y `NULL`, con opción de distinguir mayúsculas. Los filtros de distintas columnas se combinan con `AND` y actúan sobre la página ya cargada, sin reescribir ni ejecutar otra vez el SQL original. El menú contextual también ofrece copia, ordenación y acceso al editor para agregar/eliminar campos de la tabla. Estos cambios de estructura se revisan en su formulario y la eliminación solicita confirmación. Las filas marcadas conservan su identidad al ordenar o filtrar.

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

La versión 0.10.2 descarga automáticamente los adaptadores JDBC que falten para PostgreSQL, SQL Server, Oracle, SQLite y H2. Los guarda en el perfil local de Windows, comprueba una firma SHA-256 fijada por AdisDB y muestra el progreso antes de volver a intentar la conexión. MySQL y MariaDB continúan incluidos en la aplicación.

La versión 0.10.1 corrigió las copias interrumpidas por fechas cero: transfiere estos valores como texto compatible, ejecuta los nuevos lotes dentro de una transacción, permite reanudar copiando únicamente claves primarias faltantes y verifica conteos, faltantes y filas adicionales.

Cliente de bases de datos de escritorio, portable y extensible mediante JDBC.

## Funciones incluidas

- Perfiles de conexión para PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, SQLite y JDBC genérico.
- MySQL Connector/J oficial incluido y detectado automáticamente.
- MariaDB Connector/J incluido, con cambio automático cuando el servidor solicita `auth_gssapi_client`.
- Selector explícito entre usuario/contraseña y autenticación integrada de Windows (GSSAPI/SSPI) para MySQL/MariaDB.
- Descarga automática bajo demanda de los controladores JDBC registrados, con verificación SHA-256; también admite archivos `.jar` seleccionados manualmente.
- Explorador de catálogos, esquemas, tablas, vistas y columnas.
- Editor SQL con ejecución de la selección o de todas las sentencias cuando no hay selección.
- Cancelación de consultas, límite de filas, transacciones manuales, `commit` y `rollback`.
- Resultados ordenables en pestañas y exportación Excel (.xlsx), CSV, TSV, JSON, XML, HTML y SQL.
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

El resultado de esta versión queda en `dist\0.18.3\AdisDB\AdisDB.exe` e incluye un runtime Java reducido.

La distribución limita el heap a 256 MB y usa renderizado GDI por software para evitar problemas de controladores gráficos en Windows.

## Actualizaciones

Para el usuario final, **Ayuda → Buscar actualizaciones** consulta el canal incorporado al generar el ejecutable. **Canal de actualizaciones (administrador)** solamente se usa para probar o reemplazar ese canal.

El actualizador descarga el paquete de la plataforma en segundo plano, compara su SHA-256 con el manifiesto y solo entonces permite instalarlo. En Windows y en instalaciones macOS por usuario, la instalación se realiza después de cerrar AdisDB y la aplicación vuelve a abrirse.

`package.ps1` genera `dist\update-manifest.json` e incorpora el canal en el ejecutable cuando recibe la dirección pública:

```powershell
.\package.ps1 -UpdateBaseUrl 'https://tu-servidor.example/nexodb'
```

Publica juntos el manifiesto y el ZIP producido. Sin una dirección pública donde alojarlos no existe un canal remoto que la aplicación pueda consultar.

## Conectarse

1. Abre **Nueva conexión**.
2. Elige el motor, completa host, puerto, base de datos y usuario.
3. Para MySQL y MariaDB no necesitas seleccionar ningún archivo: sus controladores oficiales ya están incluidos.
4. Para PostgreSQL, SQL Server, Oracle, SQLite, H2 y MongoDB, AdisDB descarga automáticamente el controlador oficial si falta. También puedes comprobarlo antes con **Verificar / descargar driver**.
5. Escribe la contraseña y pulsa **Probar** o **Guardar y conectar**.

En MySQL puedes dejar **Base / servicio / archivo** vacío. AdisDB conectará al servidor y mostrará en **Objetos** todas las bases, tablas y vistas que el usuario tenga permiso de consultar.

En PostgreSQL también puedes dejar **Base / servicio / archivo** vacío: AdisDB consulta las bases disponibles y permite elegir una antes de cargar sus esquemas y objetos. Para consultar el catálogo utiliza inicialmente `postgres` y, si esa base no existe o no permite la conexión, prueba `template1`. Si el usuario no puede conectarse a ninguna de las dos, debes indicar una base existente a la que sí tenga acceso. PostgreSQL necesita una base inicial para consultar el servidor.

Los campos URL JDBC, clase y archivo del controlador están dentro de **Configuración avanzada**, colapsada por defecto. Para conexiones normales no necesitas modificarlos.

AdisDB usa exclusivamente el usuario y la contraseña escritos en el formulario. El usuario es obligatorio; la contraseña puede quedar vacía y se envía explícitamente como `password=""`. Nunca se sustituyen esos datos por la cuenta activa de Windows. El servidor debe tener una cuenta compatible con esas credenciales.

Si seleccionas **Windows integrada (GSSAPI/SSPI)**, la contraseña deja de enviarse y AdisDB usa deliberadamente las credenciales de la sesión actual de Windows. El campo Usuario continúa identificando la cuenta MariaDB configurada con GSSAPI, por ejemplo `root`.

Los perfiles guardan la configuración, pero no la contraseña. Para SQLite, `Host` puede quedar vacío y en `Base de datos` se indica la ruta del archivo.

## Atajos

- `Ctrl+Enter` o `F5`: ejecutar la selección; sin selección, todas las sentencias del editor.
- `Escape`: cancelar consulta.
- `Ctrl+S`: guardar el archivo SQL; `Ctrl+Shift+S`: guardar como.
- `Ctrl+Shift+E`: exportar el resultado activo, incluido Excel (.xlsx).
- `Tab`: aceptar el autocompletado y continuar al final de la inserción.
- Doble clic en una tabla o vista: generar un `SELECT` limitado.

## Seguridad

Un controlador JDBC es código ejecutable. Usa únicamente controladores descargados de la página oficial del proveedor. Evita ejecutar modificaciones sin una copia de seguridad y usa el modo de solo lectura para conexiones de producción.

## Componente incluido

MySQL Connector/J 26.10.0, publicado por Oracle Corporation bajo GNU GPL v2 con Universal FOSS Exception v1.0. Artefacto `com.mysql:mysql-connector-j:26.10.0`, obtenido de Maven Central y verificado con SHA-256 `cef70caf532833c9ab8f41f74773ce22c08bf503fad215c9c2ee1e9db87e759e`.

MariaDB Connector/J 4.0.0, publicado bajo GNU LGPL 2.1. Artefacto `org.mariadb.jdbc:mariadb-java-client:4.0.0`, verificado con SHA-256 `bb1211dac59ef4c54b7a8ad4755a913d36d2e81b34ca1d4cb9ffa9d5633df01f`.

MariaDB Connector/J 3.5.11 y Waffle-JNA 3.6.0 se incluyen para autenticación integrada SSPI de Windows. El modo normal continúa usando Connector/J 4.0.0 y nunca consulta credenciales del sistema.

FlatLaf 3.7.2, publicado por FormDev bajo Apache License 2.0. Artefacto `com.formdev:flatlaf:3.7.2`, verificado con SHA-256 `917aff3963c88d797d0fd9b9ccbd70f7681c101df9d11c59e2bc7a3a6c0fabf4`.

FlatLaf Extras 3.7.2 y JSVG 2.1.0 renderizan los SVG. Los logotipos de motores proceden de Devicon (MIT) y del proyecto H2; las marcas pertenecen a sus respectivos propietarios y se usan únicamente para identificar el motor seleccionado.

JSch 2.28.0, mantenido por Matthias Wiedemann y colaboradores bajo licencia BSD, proporciona los túneles SSH incluidos en AdisDB. El artefacto `com.github.mwiede:jsch:2.28.0` se verifica con SHA-256 `916f950708937fa1e7813f1e0b6879854e456c10770033dee54fa4df1de73974`.

