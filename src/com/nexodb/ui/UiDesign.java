package com.nexodb.ui;

import com.nexodb.util.I18n;
import javax.swing.*;
import java.awt.*;

/** Consistent semantic action icons and toolbar spacing, independent of the selected language. */
public final class UiDesign {
    private UiDesign(){}
    public static IconFactory.Kind icon(String caption){
        String s=I18n.source(caption).toLowerCase(java.util.Locale.ROOT);
        if(s.contains("deshacer"))return IconFactory.Kind.UNDO;if(s.contains("rehacer"))return IconFactory.Kind.REDO;
        if(s.contains("eliminar"))return IconFactory.Kind.DELETE;if(s.contains("cerrar")||s.contains("quitar")||s.contains("cancelar"))return IconFactory.Kind.CANCEL;
        if(s.contains("guardar"))return IconFactory.Kind.SAVE;if(s.contains("abrir"))return s.contains("datos")?IconFactory.Kind.TABLE:IconFactory.Kind.FOLDER;
        if(s.contains("importar"))return IconFactory.Kind.IMPORT;if(s.contains("exportar"))return IconFactory.Kind.EXPORT;
        if(s.contains("respaldo"))return IconFactory.Kind.BACKUP;if(s.contains("historial"))return IconFactory.Kind.HISTORY;
        if(s.contains("editar")||s.contains("modificar")||s.contains("renombrar"))return IconFactory.Kind.EDIT;
        if(s.contains("copiar")||s.contains("duplicar"))return IconFactory.Kind.COPY;if(s.contains("filtrar"))return IconFactory.Kind.FILTER;
        if(s.contains("actualizar")||s.contains("recargar")||s.contains("reconstruir"))return IconFactory.Kind.REFRESH;
        if(s.contains("ejecutar"))return IconFactory.Kind.PLAY;if(s.contains("buscar"))return IconFactory.Kind.SEARCH;
        if(s.contains("conectar"))return s.contains("desconectar")?IconFactory.Kind.DISCONNECT:IconFactory.Kind.CONNECT;
        if(s.contains("comparar")||s.contains("comparación")||s.contains("sincronizar"))return IconFactory.Kind.COMPARE;
        if(s.contains("diseñador")||s.contains("diagrama"))return IconFactory.Kind.DIAGRAM;
        if(s.contains("clave foránea")||s.contains("relación")||s.contains("relaciones")||s.contains("índice"))return IconFactory.Kind.KEY;
        if(s.contains("nueva")||s.contains("nuevo")||s.contains("agregar"))return IconFactory.Kind.ADD;
        if(s.contains("herramientas"))return IconFactory.Kind.TOOLS;if(s.contains("preferencias")||s.contains("configura"))return IconFactory.Kind.SETTINGS;
        if(s.equals("archivo"))return IconFactory.Kind.FOLDER;if(s.contains("ayuda"))return IconFactory.Kind.INFO;if(s.equals("consulta"))return IconFactory.Kind.SQL;
        return null;
    }
    public static void enhance(Component component){
        if(component instanceof JMenuBar bar)bar.setBorder(BorderFactory.createEmptyBorder(3,6,3,6));
        if(component instanceof JToolBar toolbar){toolbar.setFloatable(false);toolbar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,ThemeManager.color(0xE2E8F0,0x38424C)),BorderFactory.createEmptyBorder(7,8,7,8)));}
        if(component instanceof AbstractButton button&&!(button instanceof JCheckBox)&&!(button instanceof JRadioButton)&&button.getText()!=null){
            var kind=icon(button.getText());if(kind!=null)button.setIcon(IconFactory.of(kind,17));button.setIconTextGap(7);
            if(kind==IconFactory.Kind.PLAY&&button instanceof JButton&&button.getParent() instanceof JToolBar){button.putClientProperty("JButton.buttonType",null);button.setBackground(new Color(0x167567));button.setForeground(Color.WHITE);button.setIcon(IconFactory.of(kind,17,Color.WHITE));}
            if(button instanceof JMenu)button.setMargin(new Insets(5,9,5,9));else if(button.getParent() instanceof JToolBar){button.setMargin(new Insets(6,9,6,9));if(button.getToolTipText()==null)UiLanguage.tooltip(button,UiLanguage.key(I18n.source(button.getText())));}
        }
        if(component instanceof JMenu menu)for(Component child:menu.getMenuComponents())enhance(child);else if(component instanceof Container container)for(Component child:container.getComponents())enhance(child);
        if(component instanceof JFrame frame&&frame.getJMenuBar()!=null)enhance(frame.getJMenuBar());
    }
}
