package com.nexodb.ui;

import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

final class SqlLineNumbers extends JComponent {
    private final SqlEditorPane editor;
    SqlLineNumbers(SqlEditorPane editor){
        this.editor=editor;
        setFont(editor.getFont());
        editor.getDocument().addDocumentListener(new DocumentListener(){
            public void insertUpdate(DocumentEvent e){refresh();}
            public void removeUpdate(DocumentEvent e){refresh();}
            public void changedUpdate(DocumentEvent e){refresh();}
        });
        editor.addCaretListener(e->repaint());
        editor.addPropertyChangeListener("sqlErrors",e->repaint());
        editor.addPropertyChangeListener("font",e->{setFont(editor.getFont());refresh();});
        editor.addComponentListener(new ComponentAdapter(){public void componentResized(ComponentEvent e){refresh();}});
        refresh();
    }
    // The row-header viewport must scroll through the same document height as the editor.
    @Override public Dimension getPreferredSize(){
        int digits=Math.max(3,Integer.toString(editor.getDocument().getDefaultRootElement().getElementCount()).length());
        return new Dimension(digits*getFontMetrics(getFont()).charWidth('0')+14,editor.getPreferredSize().height);
    }
    private void refresh(){revalidate();repaint();}
    protected void paintComponent(Graphics graphics){
        graphics.setColor(ThemeManager.color(0xF1F5F9,0x30343B));graphics.fillRect(0,0,getWidth(),getHeight());graphics.setFont(getFont());
        var root=editor.getDocument().getDefaultRootElement();Rectangle clip=graphics.getClipBounds();int current=root.getElementIndex(editor.getCaretPosition());
        int first=root.getElementIndex(editor.viewToModel2D(new Point(0,clip.y)));int last=root.getElementIndex(editor.viewToModel2D(new Point(0,clip.y+clip.height)));
        for(int line=Math.max(0,first);line<=last;line++)try{var bounds=editor.modelToView2D(root.getElement(line).getStartOffset());if(bounds==null)continue;
            String value=Integer.toString(line+1);boolean error=editor.errorLines().containsKey(line+1);graphics.setColor(error?new Color(0xE44250):line==current?ThemeManager.color(0x155EEF,0x82AAFF):ThemeManager.color(0x64748B,0x9BA8BB));if(error)graphics.fillOval(2,(int)bounds.getY()+5,6,6);graphics.drawString(value,getWidth()-8-graphics.getFontMetrics().stringWidth(value),(int)bounds.getY()+graphics.getFontMetrics().getAscent());
        }catch(javax.swing.text.BadLocationException ignored){}
    }
}
