---
name: sql-debugger
description: Trabajar en el depurador MySQL/MariaDB de AdisDB. Usar para procedimientos almacenados, variables, puntos de quiebre, avance de instrucciones, streaming JDBC y errores SQLSTATE.
---

# Depurador MySQL/MariaDB de AdisDB

1. Reproduce el caso en la instancia de pruebas. Usa un procedimiento corto
   que muestre el problema, por ejemplo variables asignadas con `SET`,
   varios `SELECT`, un bloque `IF` o una salida con `LEAVE`.
2. Identifica en qué fase falla: lectura de la definición, instrumentación,
   ejecución, pausa, lectura de resultados, cancelación o limpieza.
   Conserva el mensaje original del motor o del controlador JDBC.
3. Revisa la conexión dedicada de ejecución y la conexión de control.
   Preserva el procedimiento original y verifica la limpieza de los objetos
   auxiliares tanto al terminar como al cancelar.
4. Si aparece `Streaming result set ... is still active` con SQLSTATE `S1000`,
   revisa el ciclo de vida de los resultados múltiples del `CALL`. Verifica
   la configuración de `netTimeoutForStreamingResults=0` en la conexión
   dedicada de MySQL Connector/J. Mantén los resultados disponibles durante
   la ejecución y evita repetir un `SELECT` para mostrarlos.
5. Comprueba que F5 continúe al siguiente punto y F10 ejecute una instrucción
   completa. Verifica que el visor muestre valores reales de parámetros y
   variables según el ámbito activo. Si el motor no informa una posición
   exacta, distingue la instrucción asociada de la ubicación exacta del error.

## Pruebas del repositorio

Si el script sigue disponible, usa una instancia aislada y ejecuta:

- MariaDB Connector/J: `./tools/test-stored-programs.ps1 -WithUi`
- MySQL Connector/J: `./tools/test-stored-programs.ps1 -MysqlDriver -WithUi`

Comprueba pausas, cambios de variables, NULL, parámetros de salida, varios
conjuntos de resultados y cancelación según el alcance del cambio. Informa
qué controlador y qué escenarios verificaste.