package com.nexodb.ui;

import com.nexodb.ai.*;
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
    private final JCheckBox remember=new JCheckBox(tr("Recordar clave cifrada (Windows)"));
    private final JComboBox<AiClient.Task> task=new JComboBox<>(AiClient.Task.values());
    private final JTextArea instruction=new JTextArea(4,60),schema=new JTextArea(),plan=new JTextArea(),answer=new JTextArea();
    private final SqlEditorPane sql=new SqlEditorPane(),proposal=new SqlEditorPane();
    private final JLabel status=new JLabel(" ");
    private final JButton send=UiLanguage.button("Generar propuesta"),cancel=UiLanguage.button("Cancelar"),open=UiLanguage.button("Abrir SQL en consulta nueva");
    private final Consumer<String> openSql;
    private final SecretStore secrets=new SecretStore();
    private SwingWorker<AiClient.Answer,Void> worker;
    private String secretEndpoint="";
    public AiAssistantPanel(String initialSql,String context,Consumer<String> openSql) {
        super(new BorderLayout(8,8));this.openSql=openSql;setBorder(BorderFactory.createEmptyBorder(10,10,10,10));setName("ai-assistant");
        provider.setSelectedItem(providerFromSettings());AiConfig.Provider initialProvider=(AiConfig.Provider)provider.getSelectedItem();endpoint.setText(AppSettings.get("ai.endpoint",initialProvider.defaultEndpoint()));model.setText(AppSettings.get("ai.model",initialProvider.defaultModel()));
        sql.restoreText(initialSql);schema.setText(context);schema.setName("ai-schema-context");plan.setName("ai-plan-context");answer.setEditable(false);answer.setLineWrap(true);answer.setWrapStyleWord(true);
        JPanel settings=new JPanel(new GridLayout(0,1,3,3));JPanel p=new JPanel(new com.nexodb.reporting.ui.WrapLayout());p.add(UiLanguage.label("Proveedor:"));p.add(provider);p.add(UiLanguage.label("Modelo:"));p.add(model);settings.add(p);
        p=new JPanel(new com.nexodb.reporting.ui.WrapLayout());p.add(new JLabel("Endpoint:"));p.add(endpoint);p.add(UiLanguage.label("Clave API:"));p.add(key);settings.add(p);
        p=new JPanel(new com.nexodb.reporting.ui.WrapLayout());remember.setEnabled(SecretStore.isSupported());p.add(remember);JButton save=UiLanguage.button("Guardar configuración");save.addActionListener(e->saveSettings());p.add(save);settings.add(p);add(settings,BorderLayout.NORTH);
        JPanel request=new JPanel(new BorderLayout(5,5));JPanel mode=new JPanel(new BorderLayout());mode.add(task,BorderLayout.WEST);mode.add(new JScrollPane(instruction));request.add(mode,BorderLayout.NORTH);
        JTabbedPane inputs=new JTabbedPane();UiLanguage.addTab(inputs,"SQL de entrada",null,new JScrollPane(sql));UiLanguage.addTab(inputs,"Contexto de esquema",null,new JScrollPane(schema));UiLanguage.addTab(inputs,"Plan de ejecución",null,new JScrollPane(plan));request.add(inputs);
        JTabbedPane outputs=new JTabbedPane();UiLanguage.addTab(outputs,"Explicación y recomendaciones",null,new JScrollPane(answer));UiLanguage.addTab(outputs,"SQL propuesto",null,new JScrollPane(proposal));
        JSplitPane split=new JSplitPane(JSplitPane.VERTICAL_SPLIT,request,outputs);split.setResizeWeight(.5);split.setDividerLocation(280);add(split);
        JPanel bottom=new JPanel(new BorderLayout());JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));actions.add(send);actions.add(cancel);actions.add(open);bottom.add(actions,BorderLayout.NORTH);bottom.add(status,BorderLayout.SOUTH);add(bottom,BorderLayout.SOUTH);
        send.addActionListener(e->send());cancel.addActionListener(e->{if(worker!=null)worker.cancel(true);});open.addActionListener(e->{if(!proposal.getText().isBlank())openSql.accept(proposal.getText());});cancel.setEnabled(false);open.setEnabled(false);
        provider.addActionListener(e->{AiConfig.Provider selected=(AiConfig.Provider)provider.getSelectedItem();endpoint.setText(selected.defaultEndpoint());model.setText(selected.defaultModel());model.setEnabled(selected!=AiConfig.Provider.VICKY);key.setText("");secretEndpoint="";});model.setEnabled(initialProvider!=AiConfig.Provider.VICKY);
        endpoint.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){private void changed(){if(!endpoint.getText().equals(secretEndpoint)){key.setText("");secretEndpoint="";}}public void insertUpdate(javax.swing.event.DocumentEvent e){changed();}public void removeUpdate(javax.swing.event.DocumentEvent e){changed();}public void changedUpdate(javax.swing.event.DocumentEvent e){}});
        status.setText(tr("Se enviarán la solicitud, el SQL y el contexto visibles al endpoint elegido. No se envían filas de resultados automáticamente."));loadSavedKey();
    }
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
        AiConfig config;try{config=config();}catch(Exception error){showError(error);return;}
        char[] password=key.getPassword();AiClient.Task operation=(AiClient.Task)task.getSelectedItem();String request=instruction.getText(),sqlInput=sql.getText(),schemaInput=schema.getText(),planInput=plan.getText();
        send.setEnabled(false);cancel.setEnabled(true);open.setEnabled(false);UiLanguage.text(status,UiLanguage.key("Esperando propuesta de IA…"));
        worker=new SwingWorker<>() {
            protected AiClient.Answer doInBackground()throws Exception{try{return new AiClient().request(config,password,operation,request,sqlInput,schemaInput,planInput);}finally{Arrays.fill(password,'\0');}}
            protected void done(){send.setEnabled(true);cancel.setEnabled(false);if(isCancelled()){UiLanguage.text(status,UiLanguage.key("Solicitud de IA cancelada."));return;}try{var response=get();answer.setText(response.text());answer.setCaretPosition(0);proposal.restoreText(response.sql());open.setEnabled(!response.sql().isBlank());UiLanguage.text(status,UiLanguage.key("Propuesta lista. Revisa el SQL antes de abrirlo en el editor."));}catch(Exception error){showError(error);}}
        };worker.execute();
    }
    public void dispose(){if(worker!=null)worker.cancel(true);key.setText("");}
    private void showError(Throwable error){if(error instanceof java.util.concurrent.ExecutionException&&error.getCause()!=null)error=error.getCause();status.setText(error.getMessage());}
}
