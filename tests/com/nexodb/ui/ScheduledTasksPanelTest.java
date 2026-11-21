package com.nexodb.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.nexodb.MetadataFixture;
import com.nexodb.db.JdbcSession;
import com.nexodb.db.ScheduledTasks;
import com.nexodb.model.*;
import com.nexodb.tools.ExternalTaskScheduler;
import javax.swing.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class ScheduledTasksPanelTest {
    public static void main(String[] args)throws Exception{
        FlatDarkLaf.setup();Path root=Files.createTempDirectory("adisdb-tasks-panel-"),runner=Files.writeString(root.resolve("AdisDB Tasks.exe"),"test");ConnectionProfile profile=new ConnectionProfile("p","MySQL",DatabaseType.MYSQL,"localhost",3306,"ventas","user","","","",true);ExternalTaskScheduler scheduler=new ExternalTaskScheduler(ExternalTaskScheduler.Platform.WINDOWS,runner,(command,timeout)->new ExternalTaskScheduler.Result(command.contains("/Query")?0:0,"ok"));ScheduledTasks engine=new ScheduledTasks(root.resolve("tasks.properties"),id->profile,id->new char[0],message->{},false);ScheduledTasksPanel[] panel=new ScheduledTasksPanel[1];try{SwingUtilities.invokeAndWait(()->panel[0]=new ScheduledTasksPanel(engine,()->List.of(profile),profile.id(),"ventas",scheduler));JLabel status=field(panel[0],"runnerStatus",JLabel.class);await(()->status.getText().contains("activo"));JButton install=field(panel[0],"installRunner",JButton.class),remove=field(panel[0],"removeRunner",JButton.class),run=field(panel[0],"runRunner",JButton.class),result=field(panel[0],"viewResult",JButton.class);equal(true,install.isEnabled());require(install.getText().contains("Actualizar"),install.getText());equal(true,remove.isEnabled());equal(true,run.isEnabled());equal(false,result.isEnabled());require(panel[0].getBackground()!=null,"Dark panel background missing");}finally{engine.close();}
        require(ScheduledTasksPanel.diagnostic("Access denied for user 'amite'@'186.3.217.69'").contains("No es la contraseña de Windows"),"Access denied diagnosis missing");require(ScheduledTasksPanel.diagnostic("Access denied for user 'amite'@'186.3.217.69' (using password: NO)").contains("no recibió una contraseña"),"Missing-password diagnosis missing");require(ScheduledTasksPanel.diagnostic("Cancelada por el usuario").contains("No es un error de contraseña"),"Cancellation diagnosis missing");require(ScheduledTasksPanel.diagnostic("Interrumpida al cerrar AdisDB").contains("reintentará"),"Close handoff diagnosis missing");require(ScheduledTasksPanel.diagnostic("Unable to load example.dll").contains("DLL"),"DLL diagnosis missing");require(ScheduledTasksPanel.requiresCredentialReentry("Access denied for user",new char[0],AuthenticationMode.DATABASE),"Rejected stored credential should require reentry");require(!ScheduledTasksPanel.requiresCredentialReentry("Access denied for user","nueva".toCharArray(),AuthenticationMode.DATABASE),"Typed credential should be accepted for verification");require(!ScheduledTasksPanel.requiresCredentialReentry("Access denied for user",new char[0],AuthenticationMode.WINDOWS),"Integrated authentication must not request a database password");
        try(JdbcSession mysql=MetadataFixture.session("mysql",DatabaseType.MYSQL,(p,m,a)->switch(m.getName()){case "getCatalogs"->MetadataFixture.rows(new String[]{"TABLE_CAT"},new Object[]{"ventas"},new Object[]{"archivo"});default->throw new AssertionError(m.getName());})){equal(List.of("archivo","db1","ventas"),ScheduledTasksPanel.databaseNames(mysql.profile(),mysql.connection()));}
        try(JdbcSession postgres=MetadataFixture.session("postgres",DatabaseType.POSTGRESQL,(p,m,a)->{throw new AssertionError(m.getName());})){equal(List.of("db1"),ScheduledTasksPanel.databaseNames(postgres.profile(),postgres.connection()));}
        ConnectionProfile secured=new ConnectionProfile("secured","Secured",DatabaseType.H2,"",0,"PUBLIC","sa","jdbc:h2:mem:scheduledCredential;DB_CLOSE_DELAY=-1","org.h2.Driver","",false);try(JdbcSession ignored=JdbcSession.open(secured,"correcta".toCharArray())){}ScheduledTasksPanel.validateTaskConnection(secured,"correcta".toCharArray(),"PUBLIC");Instant normalized=ScheduledTasksPanel.scheduledInstant(Date.from(Instant.parse("2026-11-19T15:55:47Z")));equal(0,normalized.atZone(ZoneId.systemDefault()).getSecond());
        System.out.println("ScheduledTasksPanelTest: OK (dark UI, verified independent credentials, detailed diagnostics, database selector, daily controls and external runner)");
    }
    private static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(System.nanoTime()<end){AtomicBoolean value=new AtomicBoolean();SwingUtilities.invokeAndWait(()->value.set(condition.getAsBoolean()));if(value.get())return;Thread.sleep(25);}throw new AssertionError("Timed out");}
    private static <T>T field(Object target,String name,Class<T> type){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(target));}catch(Exception error){throw new RuntimeException(error);}}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
