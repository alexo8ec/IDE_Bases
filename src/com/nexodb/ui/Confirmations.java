package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;
import javax.swing.*;
import java.awt.*;

/** Mutating commands explain their effect and default to No. Read operations do not use this prompt. */
public final class Confirmations {
    private Confirmations(){}
    public static boolean ask(Component owner,String title,String detail){
        if(GraphicsEnvironment.isHeadless())return false;
        JTextArea text=new JTextArea(detail);text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);text.setColumns(65);text.setRows(Math.min(15,Math.max(4,detail.split("\n",-1).length+1)));text.setOpaque(false);
        Object[] options={tr("Sí"),tr("No")};return JOptionPane.showOptionDialog(owner,new JScrollPane(text),tr(title),JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE,null,options,options[1])==0;
    }
}
