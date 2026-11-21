package com.nexodb.ui;

import com.nexodb.ai.*;
import com.nexodb.db.*;
import com.nexodb.util.*;
import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.util.*;
import java.util.function.*;
import static com.nexodb.util.I18n.tr;

/** Every request is user-triggered and its SQL/context are visible and editable before sending. */
public final class AiAssistantPanel extends JPanel {
    private final JComboBox<AiConfig.Provider> provider=new JComboBox<>(AiConfig.Provider.values());
    private final JTextField endpoint=new JTextField(40),model=new JTextField(20);
    private final JPasswordField key=new JPasswordField(25);
    private final JCheckBox remember=new JCheckBox(tr("Recordar clave en el almacén seguro del sistema"));
    private final JComboBox<AiClient.Task> task=new JComboBox<>(AiClient.Task.values());
    private final JTextArea instruction=new JTextArea(4,60),schema=new JTextArea(),plan=new JTextArea(),answer=new JTextArea();
    private final SqlEditorPane sql=new SqlEditorPane(),proposal=new SqlEditorPane();
    private final JLabel status=new JLabel(" ");
    private final JLabel contextStatus=UiLanguage.label("Contexto manual: revisa motor, columnas e índices antes de enviar.");
    private final JTextArea preview=new JTextArea(),diagnostics=new JTextArea();
    private final JTabbedPane inputs=new JTabbedPane();
    private final JButton refreshContext=UiLanguage.button("Actualizar contexto de tablas");
    private final JButton send=UiLanguage.button("Generar propuesta"),cancel=UiLanguage.button("Cancelar"),open=UiLanguage.button("Abrir SQL en consulta nueva");
    private final Consumer<String> openSql;
    private final SecretStore secrets=new SecretStore();
    private SwingWorker<AiClient.Answer,Void> worker;
    private String secretEndpoint="";
    private JdbcSession contextSource;private String contextDatabase="",contextSql="";
    private Supplier<ContextSelection> contextResolver;
    private SwingWorker<AiSchemaContext.Snapshot,Void> contextWorker;
    private boolean pendingSendAfterContext;
    private boolean disposed;
    private final javax.swing.Timer previewTimer=new javax.swing.Timer(200,e->updatePreview());
    public AiAssistantPanel(String initialSql,String context,Consumer<String> openSql) {
        super(new BorderLayout(8,8));this.openSql=openSql;setBorder(BorderFactory.createEmptyBorder(10,10,10,10));setName("ai-assistant");
        provider.setSelectedItem(providerFromSettings());AiConfig.Provider initialProvider=(AiConfig.Provider)provider.getSelectedItem();endpoint.setText(AppSettings.get("ai.endpoint",initialProvider.defaultEndpoint()));model.setText(AppSettings.get("ai.model",initialProvider.defaultModel()));
        sql.restoreText(initialSql);schema.setText(context);schema.setName("ai-schema-context");plan.setName("ai-plan-context");answer.setEditable(false);answer.setLineWrap(true);answer.setWrapStyleWord(true);
        JPanel settings=new JPanel(new GridLayout(0,1,3,3));JPanel p=new JPanel(new com.nexodb.reporting.ui.WrapLayout());p.add(UiLanguage.label("Proveedor:"));p.add(provider);p.add(UiLanguage.label("Modelo:"));p.add(model);settings.add(p);
        p=new JPanel(new com.nexodb.reporting.ui.WrapLayout());p.add(new JLabel("Endpoint:"));p.add(endpoint);p.add(UiLanguage.label("Clave API:"));p.add(key);settings.add(p);
        p=new JPanel(new com.nexodb.reporting.ui.WrapLayout());remember.setEnabled(SecretStore.isSupported());p.add(remember);JButton save=UiLanguage.button("Guardar configuración");save.addActionListener(e->saveSettings());p.add(save);settings.add(p);add(settings,BorderLayout.NORTH);
        JPanel request=new JPanel(new BorderLayout(5,5));JPanel mode=new JPanel(new BorderLayout(8,0));mode.add(task,BorderLayout.WEST);mode.add(new JScrollPane(instruction));JPanel requestTop=new JPanel(new BorderLayout(4,4));requestTop.add(mode);JPanel contextBar=new JPanel(new BorderLayout(8,0));contextBar.add(refreshContext,BorderLayout.WEST);contextBar.add(contextStatus);requestTop.add(contextBar,BorderLayout.SOUTH);request.add(requestTop,BorderLayout.NORTH);
        UiLanguage.addTab(inputs,"SQL existente (opcional)",null,new JScrollPane(sql));UiLanguage.addTab(inputs,"Contexto de esquema",null,new JScrollPane(schema));UiLanguage.addTab(inputs,"Plan de ejecución",null,new JScrollPane(plan));UiLanguage.addTab(inputs,"Solicitud a enviar",null,new JScrollPane(preview));request.add(inputs);
        JTabbedPane outputs=new JTabbedPane();UiLanguage.addTab(outputs,"Explicación y recomendaciones",null,new JScrollPane(answer));UiLanguage.addTab(outputs,"SQL propuesto",null,new JScrollPane(proposal));UiLanguage.addTab(outputs,"Diagnóstico",null,new JScrollPane(diagnostics));
        preview.setName("ai-request-preview");diagnostics.setName("ai-request-diagnostics");for(JTextArea area:java.util.List.of(preview,diagnostics)){area.setEditable(false);area.setLineWrap(true);area.setWrapStyleWord(true);area.setFont(new Font(Font.MONOSPACED,Font.PLAIN,12));}
        refreshContext.setEnabled(false);refreshContext.addActionListener(e->refreshSchema());contextStatus.setName("ai-context-status");
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,request,outputs);split.setResizeWeight(.5);split.setDividerLocation(280);add(split);
        JPanel bottom=new JPanel(new BorderLayout());JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));actions.add(send);actions.add(cancel);actions.add(open);bottom.add(actions,BorderLayout.NORTH);bottom.add(status,BorderLayout.SOUTH);add(bottom,BorderLayout.SOUTH);
        send.addActionListener(e->send());cancel.addActionListener(e->{if(worker!=null)worker.cancel(true);});open.addActionListener(e->{if(!proposal.getText().isBlank())openSql.accept(proposal.getText());});cancel.setEnabled(false);open.setEnabled(false);
        provider.addActionListener(e->{AiConfig.Provider selected=(AiConfig.Provider)provider.getSelectedItem();endpoint.setText(selected.defaultEndpoint());model.setText(selected.defaultModel());model.setEnabled(selected!=AiConfig.Provider.VICKY);key.setText("");secretEndpoint="";});model.setEnabled(initialProvider!=AiConfig.Provider.VICKY);
        endpoint.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){private void changed(){if(!endpoint.getText().equals(secretEndpoint)){key.setText("");secretEndpoint="";}}public void insertUpdate(javax.swing.event.DocumentEvent e){changed();}public void removeUpdate(javax.swing.event.DocumentEvent e){changed();}public void changedUpdate(javax.swing.event.DocumentEvent e){}});
        previewTimer.setRepeats(false);var listener=new javax.swing.event.DocumentListener(){private void changed(){previewTimer.restart();}public void insertUpdate(javax.swing.event.DocumentEvent e){changed();}public void removeUpdate(javax.swing.event.DocumentEvent e){changed();}public void changedUpdate(javax.swing.event.DocumentEvent e){changed();}};
        for(var document:java.util.List.of(sql.getDocument(),schema.getDocument(),plan.getDocument(),instruction.getDocument(),endpoint.getDocument(),model.getDocument()))document.addDocumentListener(listener);
        var contextListener=new javax.swing.event.DocumentListener(){private void changed(){markContextStale();}public void insertUpdate(javax.swing.event.DocumentEvent e){changed();}public void removeUpdate(javax.swing.event.DocumentEvent e){changed();}public void changedUpdate(javax.swing.event.DocumentEvent e){changed();}};
        sql.getDocument().addDocumentListener(contextListener);instruction.getDocument().addDocumentListener(contextListener);
        task.addActionListener(e->{updateTaskMode();updatePreview();});provider.addActionListener(e->updatePreview());updateTaskMode();updatePreview();
        status.setText(tr("Se enviarán la solicitud, el SQL y el contexto visibles al endpoint elegido. No se envían filas de resultados automáticamente."));loadSavedKey();
    }
    public record ContextSelection(JdbcSession session,String database) {}
    public void setContextSource(JdbcSession session,String database){contextSource=session;contextDatabase=Objects.toString(database,"");refreshContext.setEnabled(session!=null);if(session!=null)refreshSchema();}
    public void setContextResolver(Supplier<ContextSelection> resolver){contextResolver=resolver;resolveContextSelection();}
    private void resolveContextSelection(){
        if(contextResolver==null)return;ContextSelection selected=contextResolver.get();if(selected==null)return;
        JdbcSession next=selected.session();String database=Objects.toString(selected.database(),"");
        if(next!=contextSource||!database.equals(contextDatabase)){contextSource=next;contextDatabase=database;contextSql="";schema.setText(AiSchemaContext.header(next,database));contextStatus.setText(tr("Base activa: ")+database+tr(". Actualiza el contexto antes de generar SQL."));}
        refreshContext.setEnabled(contextReady());
    }
    private boolean contextReady(){return contextSource!=null&&contextSource.isOpen()&&(!contextDatabase.isBlank()||contextSource.profile().type()==com.nexodb.model.DatabaseType.H2||contextSource.profile().type()==com.nexodb.model.DatabaseType.SQLITE);}
    private boolean generateMode(){return task.getSelectedItem()==AiClient.Task.GENERATE;}
    private String contextInput(){return generateMode()?instruction.getText():sql.getText();}
    private String contextFingerprint(){return task.getSelectedItem()+"\n"+contextDatabase+"\n"+contextInput();}
    private void markContextStale(){
        if(contextSource==null)return;boolean relevant=generateMode()?instruction.getDocument().getLength()>0:sql.getDocument().getLength()>0;
        if(relevant&&!contextSql.equals(contextFingerprint()))contextStatus.setText(generateMode()?tr("La descripción cambió. El contexto se actualizará con la base seleccionada al generar."):tr("El SQL cambió. Actualiza el contexto de sus tablas antes de enviar."));
    }
    private void updateTaskMode(){
        boolean generate=generateMode();inputs.setTitleAt(0,tr(generate?"SQL existente (opcional)":"SQL de entrada"));
        instruction.setBorder(BorderFactory.createTitledBorder(tr(generate?"Describe en lenguaje natural la consulta que necesitas":"Indica qué deseas revisar o mejorar")));
        refreshContext.setText(tr(generate?"Actualizar contexto de la base":"Actualizar contexto de tablas"));markContextStale();
    }
    private void refreshSchema(){
        resolveContextSelection();if(!contextReady()||disposed){pendingSendAfterContext=false;status.setText(tr("Selecciona una conexión y una base de datos antes de generar SQL."));return;}if(contextWorker!=null)contextWorker.cancel(true);
        JdbcSession source=contextSource;String input=contextInput(),fingerprint=contextFingerprint(),database=contextDatabase,before=schema.getText();boolean natural=generateMode();
        refreshContext.setEnabled(false);send.setEnabled(false);contextStatus.setText(tr(natural?"Leyendo objetos y columnas de la base seleccionada…":"Leyendo columnas, claves e índices de las tablas del SQL…"));
        contextWorker=new SwingWorker<>(){
            protected AiSchemaContext.Snapshot doInBackground()throws Exception{return natural?AiSchemaContext.readForRequest(source,database,input):AiSchemaContext.read(source,database,input);}
            protected void done(){
                if(disposed||contextWorker!=this)return;refreshContext.setEnabled(true);send.setEnabled(worker==null||worker.isDone());
                if(isCancelled())return;
                try{var snapshot=get();if(!fingerprint.equals(contextFingerprint())){pendingSendAfterContext=false;contextStatus.setText(tr(natural?"La descripción cambió durante la lectura. Vuelve a generar.":"El SQL cambió durante la lectura. Actualiza el contexto."));return;}
                    contextSql=fingerprint;boolean manual=!before.equals(schema.getText());if(!manual){schema.setText(snapshot.text());schema.setCaretPosition(0);}contextStatus.setText(manual?tr("Se conservó el contexto editado manualmente."):source.profile().type()+" · "+database+" · "+snapshot.summary());contextStatus.setToolTipText(String.join(" | ",snapshot.warnings()));
                    diagnostics.setText("Lectura de metadatos: "+snapshot.summary()+"\n"+String.join("\n",snapshot.warnings())+"\n\nRevisa Contexto de esquema y Solicitud a enviar antes de generar la propuesta.");
                    status.setText(tr("Contexto actualizado para ")+database+".");updatePreview();if(pendingSendAfterContext){pendingSendAfterContext=false;SwingUtilities.invokeLater(thisPanel()::send);}
                }catch(Exception error){pendingSendAfterContext=false;contextSql=fingerprint;contextStatus.setText(tr("No se pudo cargar el esquema. Revisa Diagnóstico o incorpora el contexto manualmente."));showError(error);}
            }
            private AiAssistantPanel thisPanel(){return AiAssistantPanel.this;}
        };contextWorker.execute();
    }
    private void updatePreview(){if(disposed)return;try{preview.setText(AiClient.preview(config(),(AiClient.Task)task.getSelectedItem(),instruction.getText(),sql.getText(),schema.getText(),plan.getText()));}catch(Exception error){preview.setText(tr("No se puede preparar la solicitud: ")+error.getMessage());}preview.setCaretPosition(0);}
    private AiConfig.Provider providerFromSettings(){try{return AiConfig.Provider.valueOf(AppSettings.get("ai.provider","VICKY"));}catch(IllegalArgumentException error){return AiConfig.Provider.VICKY;}}
    public void setPlanContext(String value){plan.setText(value);plan.setCaretPosition(0);task.setSelectedItem(AiClient.Task.OPTIMIZE_QUERY);}
    private AiConfig config(){return new AiConfig((AiConfig.Provider)provider.getSelectedItem(),URI.create(endpoint.getText().trim()),model.getText().trim());}
    private void loadSavedKey(){
        if(!SecretStore.isSupported())return;AiConfig initial;try{initial=config();}catch(IllegalArgumentException error){return;}
        new SwingWorker<char[],Void>(){
            protected char[] doInBackground()throws Exception{return secrets.load(initial.secretId());}
            protected void done(){try{char[] saved=get();if(saved!=null)try{if(initial.endpoint().toString().equals(endpoint.getText())&&initial.provider()==provider.getSelectedItem()&&key.getPassword().length==0){key.setText(new String(saved));secretEndpoint=endpoint.getText();remember.setSelected(true);}}finally{Arrays.fill(saved,'\0');}}catch(Exception ignored){}}
        }.execute();
    }
    private void saveSettings(){try{AiConfig config=config();AppSettings.put("ai.provider",config.provider().name());AppSettings.put("ai.endpoint",config.endpoint().toString());AppSettings.put("ai.model",config.model());char[] password=key.getPassword();new SwingWorker<Void,Void>(){protected Void doInBackground()throws Exception{try{if(remember.isSelected()&&password.length>0)secrets.save(config.secretId(),password);else secrets.delete(config.secretId());return null;}finally{Arrays.fill(password,'\0');}}protected void done(){try{get();UiLanguage.text(status,UiLanguage.key("Configuración de IA guardada."));}catch(Exception error){showError(error);}}}.execute();}catch(Exception error){showError(error);}}
    private void send(){
        if(!LicenseGate.ensure(this)||worker!=null&&!worker.isDone())return;
        if(contextWorker!=null&&!contextWorker.isDone())return;
        resolveContextSelection();if(generateMode()&&instruction.getText().isBlank()){showError(new IllegalArgumentException(tr("Describe en lenguaje natural la consulta que necesitas.")));return;}
        if(contextSource!=null&&!contextSql.equals(contextFingerprint())){pendingSendAfterContext=true;refreshSchema();status.setText(tr("Actualizando el contexto de la base seleccionada antes de generar la consulta…"));return;}
        AiConfig config;try{config=config();}catch(Exception error){showError(error);return;}
        char[] password=key.getPassword();AiClient.Task operation=(AiClient.Task)task.getSelectedItem();String request=instruction.getText(),sqlInput=sql.getText(),schemaInput=schema.getText(),planInput=plan.getText();
        String sentPreview;try{sentPreview=AiClient.preview(config,operation,request,sqlInput,schemaInput,planInput);}catch(Exception error){Arrays.fill(password,'\0');showError(error);return;}
        proposal.restoreText("");answer.setText("");diagnostics.setText("Solicitud en curso.\n\n"+sentPreview);
        send.setEnabled(false);cancel.setEnabled(true);open.setEnabled(false);UiLanguage.text(status,UiLanguage.key("Esperando propuesta de IA…"));
        worker=new SwingWorker<>() {
            protected AiClient.Answer doInBackground()throws Exception{try{return new AiClient().request(config,password,operation,request,sqlInput,schemaInput,planInput);}finally{Arrays.fill(password,'\0');}}
            protected void done(){if(disposed)return;send.setEnabled(contextWorker==null||contextWorker.isDone());cancel.setEnabled(false);if(isCancelled()){UiLanguage.text(status,UiLanguage.key("Solicitud de IA cancelada."));diagnostics.setText("Solicitud cancelada.\n\n"+sentPreview);return;}try{var response=get();showAnswer(response);diagnostics.setText(response.diagnostics()+"\n\nÚltima solicitud enviada:\n"+sentPreview);diagnostics.setCaretPosition(0);}catch(Exception error){showError(error);}}
        };worker.execute();
    }
    private void showAnswer(AiClient.Answer response){answer.setText(response.text());answer.setCaretPosition(0);proposal.restoreText(response.sql());open.setEnabled(!response.sql().isBlank());UiLanguage.text(status,UiLanguage.key(response.sql().isBlank()?"Respuesta recibida sin SQL propuesto. Revisa las explicaciones y el diagnóstico.":"Propuesta lista. Revisa el SQL antes de abrirlo en el editor."));}
    public void dispose(){disposed=true;previewTimer.stop();if(worker!=null)worker.cancel(true);if(contextWorker!=null)contextWorker.cancel(true);key.setText("");}
    private void showError(Throwable error){if(error instanceof java.util.concurrent.ExecutionException&&error.getCause()!=null)error=error.getCause();String message=Objects.toString(error.getMessage(),error.getClass().getSimpleName());status.setText(message);diagnostics.setText("Error: "+message+"\n\n"+diagnostics.getText());diagnostics.setCaretPosition(0);}
}
