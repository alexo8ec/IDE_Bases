package com.nexodb.ui;

import com.nexodb.ai.*;
import com.nexodb.db.*;
import com.nexodb.model.*;
import com.nexodb.util.*;
import com.formdev.flatlaf.FlatDarkLaf;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

public final class AiAssistantContextTest {
    public static void main(String[] args)throws Exception {
        Path settings=Files.createTempDirectory("ai-context-ui-").resolve("settings.properties");System.setProperty("nexodb.settingsFile",settings.toString());
        AppSettings.put("ai.provider","VICKY");AppSettings.put("ai.endpoint","http://127.0.0.1:1/v1/database-agent/chat");I18n.initialize("es");FlatDarkLaf.setup();
        var profile=new ConnectionProfile("ai-ui","Test",DatabaseType.H2,"",0,"mem:ai_ui","sa","jdbc:h2:mem:ai_ui","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        AtomicReference<AiAssistantPanel> ref=new AtomicReference<>();
        try(var session=JdbcSession.open(profile,new char[0])) {
            session.execute("CREATE TABLE ct_clientes(identificacion BIGINT PRIMARY KEY, nombre VARCHAR(80))",1);
            session.execute("CREATE TABLE ct_ventas(id INT PRIMARY KEY, id_cliente BIGINT)",1);session.execute("CREATE INDEX ix_cliente ON ct_ventas(id_cliente)",1);
            SwingUtilities.invokeAndWait(()->{var panel=new AiAssistantPanel("SELECT * FROM ct_ventas v JOIN ct_clientes c ON v.id_cliente=c.identificacion",AiSchemaContext.header(session,""),s->{throw new AssertionError("Unexpected SQL open");});ref.set(panel);panel.setContextSource(session,"");});
            await(ref.get());
            SwingUtilities.invokeAndWait(()->{try{
                var panel=ref.get();String schema=field(panel,"schema",JTextArea.class).getText();contains(schema,"Motor: H2");contains(schema,"IX_CLIENTE");contains(schema,"PRIMARY KEY: IDENTIFICACION");
                contains(field(panel,"contextStatus",JLabel.class).getText(),"2 tablas");
                invoke(panel,"updatePreview");contains(field(panel,"preview",JTextArea.class).getText(),"Motor: H2");
                field(panel,"key",JPasswordField.class).setText("do-not-display-this-key");invoke(panel,"updatePreview");
                if(field(panel,"preview",JTextArea.class).getText().contains("do-not-display-this-key"))throw new AssertionError("Key leaked into preview");
                var show=AiAssistantPanel.class.getDeclaredMethod("showAnswer",AiClient.Answer.class);show.setAccessible(true);show.invoke(panel,new AiClient.Answer("Falta contexto.","","HTTP 200"));
                contains(field(panel,"status",JLabel.class).getText(),"sin SQL propuesto");if(field(panel,"open",JButton.class).isEnabled())throw new AssertionError("Empty proposal can open");
                show.invoke(panel,new AiClient.Answer("Explicación","SELECT 1","HTTP 200"));if(!field(panel,"open",JButton.class).isEnabled())throw new AssertionError("Valid proposal cannot open");
                panel.setSize(1400,850);layout(panel);var image=new BufferedImage(1400,850,BufferedImage.TYPE_INT_RGB);var graphics=image.createGraphics();try{panel.paint(graphics);}finally{graphics.dispose();}Files.createDirectories(Path.of("build/report-qa"));javax.imageio.ImageIO.write(image,"png",Path.of("build/report-qa/ai-context-diagnostics.png").toFile());
                field(panel,"sql",SqlEditorPane.class).restoreText("SELECT * FROM ct_clientes");contains(field(panel,"contextStatus",JLabel.class).getText(),"SQL cambió");field(panel,"refreshContext",JButton.class).doClick();
            }catch(Exception e){throw new RuntimeException(e);}});
            await(ref.get());SwingUtilities.invokeAndWait(()->{try{contains(field(ref.get(),"contextStatus",JLabel.class).getText(),"1 tabla");if(field(ref.get(),"schema",JTextArea.class).getText().contains("IX_CLIENTE"))throw new AssertionError("Stale table context");}catch(Exception e){throw new RuntimeException(e);}});
        }finally{if(ref.get()!=null)SwingUtilities.invokeAndWait(ref.get()::dispose);}
        System.out.println("AiAssistantContextTest: OK (background schema/index loading, visible request, no credential disclosure, no-SQL status, stale context reload and headless rendering)");
    }
    private static void await(AiAssistantPanel panel)throws Exception {long end=System.nanoTime()+15_000_000_000L;while(System.nanoTime()<end){boolean[] ready={false};SwingUtilities.invokeAndWait(()->{try{ready[0]=field(panel,"refreshContext",JButton.class).isEnabled();}catch(Exception e){throw new RuntimeException(e);}});if(ready[0])return;Thread.sleep(30);}throw new AssertionError("Context worker timed out");}
    private static void invoke(Object obj,String name)throws Exception{var m=obj.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(obj);}
    private static <T>T field(Object obj,String name,Class<T> type)throws Exception{var f=obj.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(obj));}
    private static void layout(Container root){root.doLayout();for(Component c:root.getComponents())if(c instanceof Container child)layout(child);}
    private static void contains(String value,String part){if(!value.contains(part))throw new AssertionError("Missing "+part+" in "+value);}
}
