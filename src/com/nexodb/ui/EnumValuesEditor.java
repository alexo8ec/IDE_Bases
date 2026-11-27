package com.nexodb.ui;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import static com.nexodb.util.I18n.tr;

/** Ordered ENUM/SET value editor shared by new-table and existing-table designers. */
final class EnumValuesEditor {
    private EnumValuesEditor() {}

    static Optional<List<String>> edit(Component parent,String type,List<String> initial){
        DefaultListModel<String> model=new DefaultListModel<>();initial.forEach(model::addElement);
        JList<String> values=new JList<>(model);values.setName("enum-values-list");values.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JTextField input=new JTextField();input.setName("enum-value-input");
        JButton add=button("Agregar"),replace=button("Reemplazar"),remove=button("Eliminar"),up=button("Subir"),down=button("Bajar");
        Runnable update=()->{boolean selected=values.getSelectedIndex()>=0;replace.setEnabled(selected);remove.setEnabled(selected);up.setEnabled(selected&&values.getSelectedIndex()>0);down.setEnabled(selected&&values.getSelectedIndex()+1<model.size());};
        add.addActionListener(e->{model.addElement(input.getText());values.setSelectedIndex(model.size()-1);input.setText("");update.run();});
        replace.addActionListener(e->{int row=values.getSelectedIndex();if(row>=0){model.set(row,input.getText());values.setSelectedIndex(row);update.run();}});
        remove.addActionListener(e->{int row=values.getSelectedIndex();if(row>=0){model.remove(row);if(!model.isEmpty())values.setSelectedIndex(Math.min(row,model.size()-1));update.run();}});
        up.addActionListener(e->move(model,values,-1));down.addActionListener(e->move(model,values,1));
        values.addListSelectionListener(e->{if(!e.getValueIsAdjusting()){if(values.getSelectedValue()!=null)input.setText(values.getSelectedValue());update.run();}});
        input.addActionListener(e->add.doClick());
        JPanel entry=new JPanel(new BorderLayout(6,0));entry.add(UiLanguage.label("Valor"),BorderLayout.WEST);entry.add(input);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,5,0));for(JButton button:List.of(add,replace,remove,up,down))actions.add(button);
        JPanel south=new JPanel(new BorderLayout(0,7));south.add(entry,BorderLayout.NORTH);south.add(actions);
        JPanel editor=new JPanel(new BorderLayout(7,7));editor.setName("enum-values-editor");editor.add(new JScrollPane(values));editor.add(south,BorderLayout.SOUTH);editor.setPreferredSize(new Dimension(520,430));
        if(!model.isEmpty())values.setSelectedIndex(0);update.run();
        while(true){
            int choice=JOptionPane.showConfirmDialog(DialogPosition.owner(parent),editor,tr("Valores de ")+type.toUpperCase(Locale.ROOT),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE);
            if(choice!=JOptionPane.OK_OPTION)return Optional.empty();
            List<String> result=new ArrayList<>();for(int i=0;i<model.size();i++)result.add(model.get(i));
            if(result.isEmpty()){JOptionPane.showMessageDialog(DialogPosition.owner(parent),tr("Agrega al menos un valor para ENUM/SET."),tr("Valores ENUM/SET"),JOptionPane.WARNING_MESSAGE);continue;}
            if(new HashSet<>(result).size()!=result.size()){JOptionPane.showMessageDialog(DialogPosition.owner(parent),tr("No repitas valores en ENUM/SET."),tr("Valores ENUM/SET"),JOptionPane.WARNING_MESSAGE);continue;}
            return Optional.of(List.copyOf(result));
        }
    }

    private static JButton button(String text){return UiLanguage.text(new JButton(tr(text)),UiLanguage.key(text));}
    private static void move(DefaultListModel<String> model,JList<String> list,int delta){int row=list.getSelectedIndex(),next=row+delta;if(row<0||next<0||next>=model.size())return;String value=model.remove(row);model.add(next,value);list.setSelectedIndex(next);}
}
