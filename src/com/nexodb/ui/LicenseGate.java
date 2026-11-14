package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;
import com.nexodb.licensing.*;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.time.*;

/** Activated by the application entry point; library/component tests do not start an application. */
public final class LicenseGate {
    private static volatile boolean application;
    private LicenseGate(){}
    public static boolean start(){application=true;return ensure(null);}
    public static boolean licensed(){if(!application)return true;try{LicenseManager.configured().installed(Instant.now());return true;}catch(Exception error){return false;}}
    public static boolean ensure(Component owner){if(!application)return true;try{var manager=LicenseManager.configured();var license=manager.installed(Instant.now());manager.record(license,Instant.now());return true;}catch(Exception error){return activate(owner,error.getMessage());}}
    public static boolean show(Component owner){return activate(owner,"");}
    private static boolean activate(Component owner,String reason){
        if(GraphicsEnvironment.isHeadless())return false;JTextArea token=new JTextArea(7,65);token.setLineWrap(true);token.setWrapStyleWord(true);JLabel status=new JLabel(reason);JPanel content=new JPanel(new BorderLayout(8,10));JPanel top=new JPanel(new GridLayout(0,1,4,6));top.add(new JLabel(tr("Carga tu archivo .nexolicense o pega la clave completa.")));top.add(new JLabel(tr("Las licencias son transferibles y pueden tener vencimiento o ser ilimitadas.")));content.add(top,BorderLayout.NORTH);content.add(new JScrollPane(token));JPanel bottom=new JPanel(new BorderLayout(8,6));JButton browse=new JButton(tr("Cargar licencia…"));bottom.add(browse,BorderLayout.WEST);bottom.add(status,BorderLayout.SOUTH);content.add(bottom,BorderLayout.SOUTH);
        browse.addActionListener(e->{JFileChooser chooser=new JFileChooser();chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("AdisDB license","nexolicense"));if(chooser.showOpenDialog(owner)==JFileChooser.APPROVE_OPTION)try{Path file=chooser.getSelectedFile().toPath();if(Files.size(file)>32768)throw new IllegalArgumentException(tr("Archivo de licencia no válido."));token.setText(Files.readString(file));}catch(Exception error){status.setText(error.getMessage());}});
        while(true){int answer=JOptionPane.showOptionDialog(owner,content,tr("Activar AdisDB"),JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE,IconFactory.of(IconFactory.Kind.KEY,32),new String[]{tr("Activar"),tr("Cancelar")},tr("Activar"));if(answer!=0)return false;
            try{LicenseManager manager=LicenseManager.configured();var license=manager.verify(token.getText(),Instant.now());String duration=license.unlimited()?tr("Ilimitada"):license.expires().atZone(ZoneId.systemDefault()).toLocalDate().toString();if(!Confirmations.ask(owner,"Activar licencia",license.customer()+"\n"+license.id()+"\n"+tr("Vigencia: ")+duration+"\n"+tr("¿Guardar y activar esta licencia en este equipo?")))continue;manager.activate(token.getText(),Instant.now());JOptionPane.showMessageDialog(owner,tr("Licencia activada.")+"\n"+license.customer()+"\n"+duration);return true;}catch(Exception error){status.setText(error.getMessage());}
        }
    }
}
