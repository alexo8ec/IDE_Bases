# Consultar y seleccionar referencias

Desde la versión 0.14.4, las columnas de resultados con una clave foránea real muestran un icono de llave en el encabezado. Al seleccionar una celda aparece **…**, además del menú habitual. También puedes usar **Ver referencia**, el menú contextual o **Ctrl+Espacio** desde la celda, incluso mientras editas su texto.

La ventana muestra la tabla padre, el nombre de la restricción y las columnas relacionadas. Por ejemplo, si el servidor define `ct_ventas.id_cliente → ct_clientes.identificacion`, abrir una celda de `id_cliente` consulta ese registro en `ct_clientes`. No se deducen relaciones por el nombre `id_*`; se leen las restricciones del servidor y los orígenes JDBC del resultado.

La búsqueda intenta primero una coincidencia exacta en la clave referenciada. Si no encuentra ninguna, busca el texto en las columnas compatibles de la tabla padre, sin ejecutar el texto como SQL. Puedes elegir una columna de búsqueda, cambiar el filtro, recorrer páginas y ajustar el límite. Por defecto se muestran **25 filas**. Desmarcar **Limitar filas** solicita todos los resultados de la búsqueda.

## Solo lectura y edición

- En **Solo lectura** puedes ver los registros relacionados y buscar otros valores; no hay botón para asignarlos.
- En **Editar datos**, pulsa **Seleccionar referencia** o haz doble clic en la fila padre. Confirma el cambio para incorporarlo al borrador del resultado. La base no cambia hasta pulsar **Guardar** y confirmar el guardado.
- **Cancelar**, cerrar la ventana o responder **No** conserva los valores anteriores.
- En claves compuestas se asignan todas las columnas de la relación juntas. El filtro de otras claves permite limitar la búsqueda con los valores ya introducidos; puedes desmarcarlo para elegir otra combinación completa.
- Si faltan columnas de una clave compuesta en el SELECT, la relación se puede consultar pero no asignar. Tampoco se asignan tipos que el editor no puede guardar de forma segura.
- Ordenar filas o mover columnas conserva la correspondencia con la fila original. Las consultas, filtros y asignaciones usan el esquema y la tabla capturados en el resultado.

Las relaciones dependen de los metadatos que facilita el controlador y de los permisos de lectura de la cuenta. Las consultas agregadas o expresiones sin un origen identificable no muestran relaciones inferidas. No se combinan restricciones compuestas sin nombre cuando JDBC no permite distinguirlas.

## Android

Las columnas relacionadas muestran la llave y un botón **…** junto al valor. También puedes mantener pulsada una celda y elegir **Ver referencia**. Se abre una hoja adaptada al móvil con filtro, tabla padre, paginación y selección con confirmación. En solo lectura no se permite asignar; los cambios elegidos quedan pendientes de **Guardar cambios**.

## Actualizar la APK

Usa el botón de descarga de la barra superior o **Preferencias → Buscar actualizaciones**. La pantalla muestra la versión instalada, la disponible y las notas. Antes de actualizar, guarda o descarta los cambios de resultados y finaliza las transacciones. El texto de las consultas se guarda antes de comenzar.

La aplicación consulta el manifiesto HTTPS sin reutilizar una respuesta en caché, descarga a un archivo temporal y verifica tamaño, SHA-256, identificador de aplicación, versión y certificado de firma. Solo después ofrece el archivo al instalador de Android. Se reutiliza la misma clave de firma de las versiones anteriores; no hace falta desinstalar.

Si Android solicita **Permitir desde esta fuente**, autoriza a NexoDB y vuelve. Si cancelas la instalación, **Reintentar instalación** utiliza la APK ya descargada. Android mantiene su confirmación de instalación; NexoDB conserva el identificador de la aplicación, el almacenamiento privado y Android Keystore para que conexiones, contraseñas cifradas, borradores y licencia sigan disponibles.

La interfaz de pruebas del WebView y las pruebas de actualización pertenecen al APK de instrumentación, no a la APK distribuida. Las pruebas de actualización se realizan únicamente en el emulador del proyecto.

Referencias: [búsqueda de referencias en SQLyog](https://sqlyogkb.webyog.com/article/103-looking-up-constraints-for-a-value), [instalación de aplicaciones en Android](https://developer.android.com/studio/publish), [comprobación del permiso de instalación](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls()).
