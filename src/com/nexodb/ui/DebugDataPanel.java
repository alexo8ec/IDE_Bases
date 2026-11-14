package com.nexodb.ui;

import com.nexodb.db.QueryResult;
import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** Read-only data visualizer with the same copy/filter/export operations as query results. */
final class DebugDataPanel extends JPanel {
    final JTable table;final ResultTableModel model=new ResultTableModel();
    DebugDataPanel(QueryResult result,String name,Consumer<String> status){
        super(new BorderLayout(4,4));model.setResult(result);table=new JTable(model);table.setName("debug-data-table");table.setAutoCreateRowSorter(true);table.setCellSelectionEnabled(true);table.setRowHeight(25);
        var literal=new javax.swing.table.DefaultTableCellRenderer(){protected void setValue(Object value){super.setValue(value==null?"(NULL)":value);}};literal.putClientProperty("html.disable",true);table.setDefaultRenderer(Object.class,literal);
        JScrollPane scroll=new JScrollPane(table);JTextField search=new JTextField(16);ResultGridActions grid=new ResultGridActions(table,model,scroll,search,()->{},status);grid.exportAction(format->ResultExports.write(this,model.snapshot(),name,format,status));
        JPanel bar=new JPanel(new FlowLayout(FlowLayout.LEFT));bar.add(ResultExports.button(this,model::snapshot,name,status));JButton copy=UiLanguage.button("Copiar ▾");copy.addActionListener(e->grid.showMenu(copy));bar.add(copy);bar.add(UiLanguage.label("Filtrar:"));bar.add(search);add(bar,BorderLayout.NORTH);add(scroll);
        add(new JLabel(result.rows().size()+" filas"+(result.truncated()?" · Vista previa limitada":"")),BorderLayout.SOUTH);
    }
}
