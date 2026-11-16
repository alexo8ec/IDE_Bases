---
name: ui
description: Modificar la interfaz de AdisDB, incluidos diálogos, cuadrículas, editores SQL, menús y atajos. Usar cuando el usuario describa un cambio visual o adjunte una captura de referencia.
---

# Cambios de interfaz en AdisDB

1. Identifica la pantalla y la acción que la abre. Busca el texto que aparece
   en la captura o el nombre del control. Confirma qué clase crea el componente
   y si otras pantallas reutilizan esa misma clase.
2. Aplica el cambio en el punto compartido cuando deba funcionar en varias
   pantallas. Usa los estilos y componentes existentes. Interpreta la captura
   según el requisito del usuario, por ejemplo posición, espaciado o sangría.

## Diálogos de texto y fecha

- Para «centra los diálogos», usa como referencia la ventana de AdisDB que
  abrió el diálogo. Calcula el centro después de determinar su tamaño y antes
  de mostrarlo. La posición debe ser independiente de la celda seleccionada.
- Comprueba que el contenido y los botones queden visibles con la aplicación
  maximizada, restaurada o en otro monitor. Si el contenido es demasiado
  grande, conserva el desplazamiento interno y deja los botones accesibles.
- Preserva Aceptar, Cancelar, Solo lectura, Establecer NULL y las acciones de
  copia y guardado donde correspondan. Verifica texto largo y fecha con hora.

## Editores SQL y atajos

- Registra el atajo en el editor SQL que tenga el foco. Comprueba Consulta SQL
  y los editores de vistas, procedimientos, funciones y triggers que usen
  la misma acción.
- Para formatear SQL, aplica el cambio a la selección o al documento completo
  si no hay selección. Agrupa la edición en una sola operación de deshacer.
  Conserva literales, comentarios, alias, identificadores entre comillas y
  delimitadores. Si el fragmento no se puede formatear con seguridad, déjalo
  intacto. La acción de formatear solo edita el documento.

## Verificación

Compila y verifica la pantalla afectada. Prueba tanto el flujo habitual como
el caso que reprodujo el problema. Si no puedes abrir la interfaz, indica qué
verificación visual queda pendiente. Para generar paquetes de actualización,
carga también la guía `release`.