package com.nexodb.issuer;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;

public final class IssuerWindowTest {
    public static void main(String[] args)throws Exception{
        Path temporary=Files.createTempDirectory("issuer espacio á-");
        Path config=temporary.resolve("issuer.properties");
        try{
            String expected="C:/Usuarios/María/OneDrive - Empresa/.signing/issuer.nxvault";
            Files.writeString(config,"vault.path="+expected+"\n");
            if(!IssuerApp.configuredVault(config).equals(expected))throw new AssertionError("Vault path lost spaces or Unicode");
            if(!IssuerApp.configuredVault(temporary.resolve("missing.properties")).isEmpty())throw new AssertionError("Missing config should allow manual vault selection");
        }finally{Files.deleteIfExists(config);Files.delete(temporary);}
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        SwingUtilities.invokeAndWait(()->{IssuerApp frame=new IssuerApp();try{frame.setVisible(true);frame.validate();if(field(frame,"generate",JButton.class).isEnabled()||field(frame,"save",JButton.class).isEnabled())throw new AssertionError("Locked issuer can issue a license");field(frame,"customer",JTextField.class).setText("Cliente de ejemplo");field(frame,"unit",JComboBox.class).setSelectedItem("Ilimitada");if(field(frame,"amount",JSpinner.class).isEnabled())throw new AssertionError("Unlimited duration still asks for days");BufferedImage image=new BufferedImage(frame.getWidth(),frame.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D graphics=image.createGraphics();frame.paint(graphics);graphics.dispose();Path output=Path.of("build/previews/license-issuer.png");Files.createDirectories(output.getParent());javax.imageio.ImageIO.write(image,"png",output.toFile());}catch(Exception error){throw new RuntimeException(error);}finally{frame.dispose();}});
        System.out.println("IssuerWindowTest: OK (authority locked by default, generation/save disabled until unlock, unlimited duration selection and UI rendering)");System.exit(0);
    }
    private static <T>T field(Object target,String name,Class<T> type)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(target));}
}
