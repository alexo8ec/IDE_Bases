---
name: release
description: Preparar una entrega de AdisDB. Usar para incrementar versión, generar paquetes por plataforma o preparar manifiestos y hashes de actualización.
---

# Preparar una entrega de AdisDB

1. Lee `Versiones`, `Entrega oficial` y `Git y publicación` en `vicky.md`.
   Inspecciona `VERSION`, `android/VERSION` y los manifiestos vigentes
   antes de asignar la versión.
2. Incrementa `patch` una sola vez por entrega. En una entrega completa,
   sincroniza escritorio y Android según `vicky.md`. Si esta misma entrega
   ya tiene versión asignada, reutilízala al reintentar.
3. Ejecuta el orden de `Entrega oficial`. `release.ps1` cubre Windows
   y Android. Linux y macOS requieren su empaquetado y verificación propios.
4. Usa los scripts oficiales para generar y verificar los manifiestos
   y `SHA256SUMS.txt` a partir de los artefactos reales.
5. Revisa que cada paquete corresponda a la versión y arquitectura previstas.
   Reporta por separado los estados de compilación, verificación y firma.
   Los paquetes macOS ensamblados desde Windows quedan sin firma Developer ID
   ni notarización.
6. Entrega la versión anterior y nueva, los comandos ejecutados, las rutas
   exactas de los paquetes y manifiestos, y las verificaciones pendientes.

Si el usuario solo solicita compilar o probar un cambio, sigue
`Compilación y pruebas` de `vicky.md` con la versión vigente.
Para commit, push y publicación, aplica las reglas de `Git y publicación`.