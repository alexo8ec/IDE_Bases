package com.nexodb.ui;

import com.nexodb.model.*;
import com.nexodb.util.*;
import com.formdev.flatlaf.*;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import java.lang.reflect.Field;
import java.util.*;

public final class AccountConnectionUiTest {
    public static void main(String[] args)throws Exception {
        Set<String> samples=new HashSet<>();
        for(boolean symbols:new boolean[]{false,true})for(int length:new int[]{12,20,64})for(int i=0;i<100;i++) {
            char[] value=PasswordGenerator.generate(length,symbols);String secret=new String(value);
            if(secret.length()!=length || !secret.matches(".*[A-Z].*") || !secret.matches(".*[a-z].*") || !secret.matches(".*[2-9].*") || symbols && !secret.matches(".*[!@#$%+_=?:-].*") || !symbols && !secret.matches("[A-Za-z2-9]+") || !samples.add(secret))throw new AssertionError("Generator contract");Arrays.fill(value,'\0');
        }
        SwingUtilities.invokeAndWait(()->{
            try {
                I18n.initialize("es");
                for(boolean dark:new boolean[]{false,true}) {
                    if(dark)FlatDarkLaf.setup();else FlatLightLaf.setup();
                    PasswordEditorPanel panel=new PasswordEditorPanel();
                    if(panel.copy.isEnabled() || panel.validation().isEmpty())throw new AssertionError("Empty password");
                    panel.generate.doClick(); if(!panel.validation().isEmpty() || panel.password.getPassword().length!=20 || panel.password.getEchoChar()==0)throw new AssertionError("Generate");
                    panel.show.doClick();if(panel.password.getEchoChar()!=0 || panel.confirmation.getEchoChar()!=0)throw new AssertionError("Show");panel.show.doClick();
                    panel.confirmation.setText("mismatch");if(panel.validation().isEmpty())throw new AssertionError("Mismatch");panel.generate.doClick();
                    render(panel,680,235,"password-"+(dark?"dark":"light"));panel.close();if(panel.password.getPassword().length!=0 || panel.confirmation.getPassword().length!=0)throw new AssertionError("Cleanup");
                }
                FlatDarkLaf.setup();
                if(GraphicsEnvironment.isHeadless()) return;
                ConnectionDialog dialog=new ConnectionDialog(null,null);
                try {
                    @SuppressWarnings("unchecked") JComboBox<DatabaseType> type=(JComboBox<DatabaseType>)field(dialog,"type");
                    for(DatabaseType engine:DatabaseType.values()) {
                        type.setSelectedItem(engine);
                        boolean network=engine!=DatabaseType.SQLITE && engine!=DatabaseType.H2 && engine!=DatabaseType.GENERIC;
                        visible(dialog,"host",network);visible(dialog,"port",network);visible(dialog,"username",engine!=DatabaseType.SQLITE);visible(dialog,"password",engine!=DatabaseType.SQLITE);
                        visible(dialog,"authentication",engine==DatabaseType.MYSQL || engine==DatabaseType.MARIADB);visible(dialog,"browseDatabase",engine==DatabaseType.SQLITE);
                        visible(dialog,"tlsMode",network && engine!=DatabaseType.ORACLE);
                    }
                    type.setSelectedItem(DatabaseType.SQLITE);((JTextField)field(dialog,"database")).setText("C:/xampp/htdocs/perfectgym/database/database.sqlite");
                    render((JComponent)dialog.getContentPane(),740,570,"connection-sqlite");
                    type.setSelectedItem(DatabaseType.ORACLE);render((JComponent)dialog.getContentPane(),740,620,"connection-oracle");
                    type.setSelectedItem(DatabaseType.H2);render((JComponent)dialog.getContentPane(),740,520,"connection-h2");
                    I18n.initialize("en");PasswordEditorPanel english=new PasswordEditorPanel();if(!english.generate.getText().equals("Generate password"))throw new AssertionError("English labels");english.close();
                } finally {dialog.dispose();I18n.initialize("es");}
            } catch(Exception error){throw new RuntimeException(error);}
        });
        if(!GraphicsEnvironment.isHeadless() && Files.exists(Path.of("build/h2-test.jar"))) accountWindow();
        System.out.println("AccountConnectionUiTest: OK (password generation/validation/cleanup, engine-specific fields, both themes, English)");
    }
    private static void accountWindow()throws Exception {
        ConnectionProfile profile=new ConnectionProfile("account-ui","Isolated user manager",DatabaseType.H2,"",0,"mem:account_ui","sa","jdbc:h2:mem:account_ui","org.h2.Driver",Path.of("build/h2-test.jar").toAbsolutePath().toString(),false);
        try(com.nexodb.db.JdbcSession session=com.nexodb.db.JdbcSession.open(profile,new char[0])) {
            java.util.concurrent.atomic.AtomicReference<UserManagerDialog> ref=new java.util.concurrent.atomic.AtomicReference<>();
            SwingUtilities.invokeAndWait(()->{UserManagerDialog dialog=new UserManagerDialog(null,session);dialog.addNotify();ref.set(dialog);});
            UserManagerDialog dialog=ref.get();
            try {
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                java.util.concurrent.atomic.AtomicBoolean loaded=new java.util.concurrent.atomic.AtomicBoolean();
                while(System.nanoTime()<deadline) { SwingUtilities.invokeAndWait(()->{try{loaded.set(((JButton)field(dialog,"refresh")).isEnabled());}catch(Exception error){throw new RuntimeException(error);}}); if(loaded.get())break;Thread.sleep(30); }
                if(!loaded.get())throw new AssertionError("Account load timeout");
                SwingUtilities.invokeAndWait(()->{
                    try {
                        JTable users=(JTable)field(dialog,"users"); if(users.getRowCount()!=1 || ((JButton)field(dialog,"password")).isEnabled())throw new AssertionError("Account selection state");
                        users.setRowSelectionInterval(0,0);if(!((JButton)field(dialog,"password")).isEnabled())throw new AssertionError("Password action unavailable");
                        render((JComponent)dialog.getContentPane(),980,620,"user-manager-h2");
                        javax.swing.Timer cancel=new javax.swing.Timer(200,e->{
                            for(Window window:Window.getWindows())if(window instanceof JDialog child && child!=dialog && child.isVisible()) {
                                JOptionPane pane=find(child,JOptionPane.class); PasswordEditorPanel password=find(child,PasswordEditorPanel.class);
                                try {if(password!=null){password.generate.doClick();render((JComponent)pane.getMessage(),720,480,"create-user-h2");}}
                                catch(Exception error){throw new RuntimeException(error);}finally{if(pane!=null)pane.setValue(pane.getOptions()[1]);}
                            }
                        });cancel.setRepeats(false);cancel.start();
                        ((JButton)field(dialog,"create")).doClick();
                    }catch(Exception error){throw new RuntimeException(error);}
                });
                if(new com.nexodb.db.UserAdministration(session.connection(),DatabaseType.H2).accounts().size()!=1)throw new AssertionError("Cancel created an account");
            }finally{SwingUtilities.invokeAndWait(dialog::dispose);}
        }
    }
    private static <T>T find(Container parent,Class<T> type){if(type.isInstance(parent))return type.cast(parent);for(Component c:parent.getComponents())if(c instanceof Container child){T found=find(child,type);if(found!=null)return found;}return null;}
    private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void visible(Object o,String name,boolean expected)throws Exception{if(((Component)field(o,name)).isVisible()!=expected)throw new AssertionError(name+" visibility");}
    private static void render(JComponent panel,int w,int h,String name)throws Exception{panel.setSize(w,h);layout(panel);BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();panel.printAll(g);g.dispose();Path folder=Path.of("build/previews");Files.createDirectories(folder);ImageIO.write(image,"png",folder.resolve(name+".png").toFile());}
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container p)layout(p);}
}
