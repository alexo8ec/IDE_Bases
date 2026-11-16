---
name: adisdb
description: Convenciones base para trabajar en AdisDB, repositorio IDE_Bases.
---

# AdisDB / IDE_Bases

AdisDB es una aplicación Java 17 y Swing con un módulo Android independiente.
El repositorio autorizado es `alexo8ec/IDE_Bases`; conserva los nombres
históricos `IDE_Bases`, `nexodb` y los identificadores existentes cuando sean
parte de la compatibilidad del producto.

## Forma de trabajar

1. Localiza el componente real y lee el código vigente, por rangos si el
   archivo es grande. No delegues al usuario la búsqueda de rutas indexadas.
2. Aplica parches mínimos sobre el workspace local, guarda físicamente los
   archivos y revisa el diff antes de verificar.
3. Usa las pruebas y el proceso de compilación incluidos en el repositorio.
   Corrige una verificación fallida antes de declarar terminada la tarea.
4. Para interfaz carga `ui`; para SQL o depuración carga `sql-debugger`; para
   compilar, empaquetar, publicar o generar manifiestos carga `release`.
5. Preserva licencias, conexiones, preferencias, borradores e identificadores
   del actualizador. La autoridad privada de licencias nunca se empaqueta ni
   se copia al repositorio.
