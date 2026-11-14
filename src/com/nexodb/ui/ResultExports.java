package com.nexodb.ui;

import com.nexodb.db.QueryResult;
import com.nexodb.util.DataExporter;
import javax.swing.*;
import java.awt.Component;
import java.util.function.*;
import static com.nexodb.util.I18n.tr;

/** Export the exact loaded snapshot; never re-executes the source query. */
final class ResultExports {
    private ResultExports(){}
    static JButton button(Component owner,Supplier<QueryResult> result,String name,Consumer<String> status){
        JButton button=UiLanguage.button("Exportar…");button.setName("result-export");
        button.addActionListener(e->{JPopupMenu menu=new JPopupMenu();for(var format:DataExporter.Format.values()){JMenuItem item=new JMenuItem(format.toString()+"…");item.addActionListener(a->write(owner,result.get(),name,format,status));menu.add(item);}menu.show(button,0,button.getHeight());});return button;
    }
    static void write(Component owner,QueryResult result,String name,DataExporter.Format format,Consumer<String> status){
        if(!result.hasRows())return;var request=ExportChooser.choose(owner,name,format);if(request==null)return;
        new SwingWorker<Void,Void>(){protected Void doInBackground()throws Exception{DataExporter.write(result,request.path(),request.format(),name);return null;}protected void done(){try{get();status.accept(tr("Datos exportados: ")+request.path());}catch(Exception error){Throwable cause=error.getCause()==null?error:error.getCause();JOptionPane.showMessageDialog(owner,cause.getMessage(),tr("Exportar datos"),JOptionPane.ERROR_MESSAGE);}}}.execute();
    }
}
