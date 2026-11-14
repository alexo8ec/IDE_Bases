package com.nexodb.issuer;

import com.nexodb.licensing.LicenseCodec;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

/** Administrative app: signing key material is loaded only from the encrypted external vault. */
public final class IssuerApp extends JFrame {
    private final JTextField vault=new JTextField(defaultVault()),customer=new JTextField(),id=new JTextField();private final JPasswordField password=new JPasswordField();private final JComboBox<String> unit=new JComboBox<>(new String[]{"Días","Meses","Años","Ilimitada"});private final JSpinner amount=new JSpinner(new SpinnerNumberModel(30,1,36500,1));private final JTextArea token=new JTextArea(7,65);private final JLabel status=new JLabel("Desbloquea tu autoridad privada para emitir licencias.");private final JButton generate=new JButton("Generar licencia"),save=new JButton("Guardar archivo de licencia…"),change=new JButton("Cambiar contraseña de la autoridad…");private KeyPair authority;
    private static String defaultVault(){
        String override=System.getProperty("nexodb.issuerVault");
        if(override!=null)return override;
        String launcher=System.getProperty("jpackage.app-path");
        return launcher==null?"":configuredVault(Path.of(launcher).getParent().resolve("app/issuer.properties"));
    }
    static String configuredVault(Path config){
        Properties settings=new Properties();
        try(var reader=Files.newBufferedReader(config)){settings.load(reader);return settings.getProperty("vault.path","");}
        catch(java.io.IOException ignored){return "";}
    }
    public IssuerApp(){super("NexoDB · Generador privado de licencias");setDefaultCloseOperation(EXIT_ON_CLOSE);JPanel body=new JPanel(new BorderLayout(12,12));body.setBorder(new javax.swing.border.EmptyBorder(18,20,18,20));setContentPane(body);JLabel title=new JLabel("Licencias transferibles de NexoDB Studio");title.setFont(title.getFont().deriveFont(Font.BOLD,20));body.add(title,BorderLayout.NORTH);
        JPanel form=new JPanel(new GridBagLayout());GridBagConstraints g=new GridBagConstraints();g.insets=new Insets(5,5,5,5);g.fill=GridBagConstraints.HORIZONTAL;JPanel path=new JPanel(new BorderLayout(5,0));path.add(vault);JButton browse=new JButton("…");path.add(browse,BorderLayout.EAST);field(form,g,0,"Autoridad cifrada (.nxvault):",path);field(form,g,1,"Contraseña de la autoridad:",password);JButton unlock=new JButton("Desbloquear autoridad");field(form,g,2,"",unlock);field(form,g,3,"Cliente / titular:",customer);JPanel duration=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));duration.add(amount);duration.add(unit);field(form,g,4,"Duración:",duration);id.setEditable(false);field(form,g,5,"Identificador (49 caracteres):",id);token.setEditable(false);token.setLineWrap(true);token.setWrapStyleWord(true);JPanel center=new JPanel(new BorderLayout(6,10));center.add(form,BorderLayout.NORTH);center.add(new JScrollPane(token));body.add(center);JPanel footer=new JPanel(new BorderLayout(5,6));footer.add(status,BorderLayout.NORTH);JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT));buttons.add(change);buttons.add(generate);buttons.add(save);JButton copy=new JButton("Copiar clave completa");buttons.add(copy);footer.add(buttons,BorderLayout.SOUTH);body.add(footer,BorderLayout.SOUTH);generate.setEnabled(false);save.setEnabled(false);change.setEnabled(false);
        browse.addActionListener(e->{JFileChooser chooser=new JFileChooser();if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){vault.setText(chooser.getSelectedFile().toString());authority=null;generate.setEnabled(false);change.setEnabled(false);}});unlock.addActionListener(e->unlock());unit.addActionListener(e->amount.setEnabled(!unit.getSelectedItem().equals("Ilimitada")));generate.addActionListener(e->generate());save.addActionListener(e->save());copy.addActionListener(e->{if(!token.getText().isBlank()&&ask("Copiar licencia","Se copiará la clave completa al portapapeles para entregarla al titular. ¿Continuar?"))Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(token.getText()),null);});change.addActionListener(e->changePassword());setSize(1020,620);setMinimumSize(new Dimension(850,580));setLocationRelativeTo(null);}
    private static void field(JPanel p,GridBagConstraints g,int row,String label,JComponent input){g.gridy=row;g.gridx=0;g.weightx=0;p.add(new JLabel(label),g);g.gridx=1;g.weightx=1;p.add(input,g);}
    private boolean ask(String title,String text){Object[] options={"Sí","No"};return JOptionPane.showOptionDialog(this,text,title,JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE,null,options,options[1])==0;}
    private void unlock(){char[] secret=password.getPassword();password.setText("");Path path;try{path=Path.of(vault.getText());}catch(Exception error){error(error);Arrays.fill(secret,'\0');return;}new SwingWorker<KeyPair,Void>(){protected KeyPair doInBackground()throws Exception{try{KeyPair pair=IssuerVault.load(path,secret);try(var input=IssuerApp.class.getResourceAsStream("/com/nexodb/licensing/public-key.der")){if(input==null||!Arrays.equals(input.readAllBytes(),pair.getPublic().getEncoded()))throw new GeneralSecurityException("Esta autoridad no corresponde a la clave pública de esta distribución.");}return pair;}finally{Arrays.fill(secret,'\0');}}protected void done(){try{authority=get();generate.setEnabled(true);change.setEnabled(true);status.setText("Autoridad desbloqueada. La clave privada permanece fuera del cliente.");}catch(Exception error){error(error.getCause()==null?error:error.getCause());}}}.execute();}
    private void generate(){if(authority==null)return;String name=customer.getText().trim();if(name.isBlank()){error(new IllegalArgumentException("Introduce el nombre del cliente."));return;}int count=((Number)amount.getValue()).intValue();ZonedDateTime now=ZonedDateTime.now(ZoneOffset.UTC);Instant expires=switch(unit.getSelectedIndex()){case 0->now.plusDays(count).toInstant();case 1->now.plusMonths(count).toInstant();case 2->now.plusYears(count).toInstant();default->null;};if(!ask("Generar licencia",name+"\n"+(expires==null?"Ilimitada":expires.atZone(ZoneId.systemDefault()).toLocalDate())+"\nTransferible entre equipos. ¿Generar esta licencia?"))return;try{String value=IssuerAuthority.issue(authority,name,expires);var license=LicenseCodec.verify(value,authority.getPublic(),Instant.now());id.setText(license.id());token.setText(value);save.setEnabled(true);status.setText("Licencia emitida. Entrega al cliente el archivo .nexolicense o la clave completa, no solo el identificador.");}catch(Exception error){error(error);}}
    private void save(){JFileChooser chooser=new JFileChooser();chooser.setSelectedFile(new java.io.File(id.getText()+".nexolicense"));if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path file=chooser.getSelectedFile().toPath();if(!file.getFileName().toString().endsWith(".nexolicense"))file=file.resolveSibling(file.getFileName()+".nexolicense");if(!ask("Guardar licencia","Se guardará una licencia transferible para compartir con el cliente.\n"+file+"\n¿Continuar?"))return;try{Files.writeString(file,token.getText()+"\n");status.setText("Archivo de licencia guardado: "+file);}catch(Exception error){error(error);}}
    private void changePassword(){if(authority==null)return;JPasswordField a=new JPasswordField(),b=new JPasswordField();if(JOptionPane.showConfirmDialog(this,new Object[]{"Nueva contraseña (al menos 12 caracteres):",a,"Repetir:",b},"Cambiar contraseña",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;char[] first=a.getPassword(),second=b.getPassword();try{if(!Arrays.equals(first,second))throw new IllegalArgumentException("Las contraseñas no coinciden.");if(!ask("Cambiar contraseña","Se cifrará de nuevo la autoridad con la contraseña nueva. Guárdala en un lugar privado. ¿Continuar?"))return;IssuerVault.save(Path.of(vault.getText()),authority,first);status.setText("Contraseña de la autoridad actualizada. La clave de firma no cambió.");}catch(Exception error){error(error);}finally{Arrays.fill(first,'\0');Arrays.fill(second,'\0');}}
    private void error(Throwable error){JOptionPane.showMessageDialog(this,error.getMessage(),"Licencias NexoDB",JOptionPane.ERROR_MESSAGE);}
    public static void main(String[] args){try{UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());}catch(Exception ignored){}SwingUtilities.invokeLater(()->{
        IssuerApp frame=new IssuerApp();frame.setVisible(true);
        // Native-package smoke check: exercises the shipped JVM and real entry point without unlocking a key.
        if(args.length==2&&args[0].equals("--check-startup")){
            try{
                if(!frame.isShowing()||frame.generate.isEnabled()||frame.save.isEnabled())throw new IllegalStateException("Invalid initial window state");
                if(!Files.isRegularFile(Path.of(frame.vault.getText())))throw new IllegalStateException("Configured authority not found");
                javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                Signature.getInstance("Ed25519");
                if(System.getProperty("jpackage.app-path")==null)throw new IllegalStateException("Native launcher was not used");
                Files.writeString(Path.of(args[1]),"OK: native launcher, visible locked window, authority location, encryption and signing providers\n");
                frame.dispose();System.exit(0);
            }catch(Exception error){error.printStackTrace();frame.dispose();System.exit(1);}
        }
    });}
}
