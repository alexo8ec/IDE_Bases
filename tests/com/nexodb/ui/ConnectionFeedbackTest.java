package com.nexodb.ui;

import com.formdev.flatlaf.*;
import com.nexodb.model.*;
import com.nexodb.util.I18n;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;

public final class ConnectionFeedbackTest {
    public static void main(String[] args)throws Exception {
        I18n.initialize("en");
        SwingUtilities.invokeAndWait(()->{
            try {
                ConnectionProfile profile=new ConnectionProfile("test","Test server",DatabaseType.MYSQL,"localhost",3306,"db1","demo_user","jdbc:test?password=DO_NOT_DISPLAY","","",true);
                for(boolean dark:new boolean[]{false,true}){
                    if(dark)FlatDarkLaf.setup();else FlatLightLaf.setup();
                    JPanel panel=ConnectionTestFeedback.success(profile,"MySQL 8","db1",125);
                    String visible=text(panel);
                    if(!visible.contains("Connection successful!")||!visible.contains("demo_user")||visible.contains("DO_NOT_DISPLAY")||visible.contains("jdbc:test"))throw new AssertionError(visible);
                    Path folder=Path.of("build/previews");Files.createDirectories(folder);
                    panel.setSize(570,320);layout(panel);BufferedImage image=new BufferedImage(570,320,BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics=image.createGraphics();panel.printAll(graphics);graphics.dispose();
                    ImageIO.write(image,"png",folder.resolve(dark?"connection-test-dark.png":"connection-test-light.png").toFile());
                }
                FlatLightLaf.setup();
            }catch(Exception error){throw new RuntimeException(error);}
        });
        I18n.initialize("es");System.out.println("ConnectionFeedbackTest: OK (prominent result, both themes, no password/URL)");
    }
    private static String text(Component component){
        String value=component instanceof JLabel label?label.getText():component instanceof JTextComponent input?input.getText():"";
        if(component instanceof Container parent)for(Component child:parent.getComponents())value+="\n"+text(child);
        return value;
    }
    private static void layout(Container parent){parent.doLayout();for(Component child:parent.getComponents())if(child instanceof Container nested)layout(nested);}
}
