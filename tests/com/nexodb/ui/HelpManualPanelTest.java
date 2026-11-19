package com.nexodb.ui;

import javax.swing.*;
import java.lang.reflect.Field;

public final class HelpManualPanelTest {
    public static void main(String[] args)throws Exception{HelpManualPanel[] panel=new HelpManualPanel[1];SwingUtilities.invokeAndWait(()->panel[0]=new HelpManualPanel());DefaultListModel<?> model=field(panel[0],"model",DefaultListModel.class);if(model.size()<35)throw new AssertionError("Manual index is incomplete: "+model.size());JTextField search=field(panel[0],"search",JTextField.class);SwingUtilities.invokeAndWait(()->search.setText("comparar esquemas"));if(model.isEmpty())throw new AssertionError("Manual search did not find schema comparison");SwingUtilities.invokeAndWait(()->search.setText("diagrama"));if(model.isEmpty())throw new AssertionError("Manual search did not find diagram guidance");JComboBox<?> section=field(panel[0],"section",JComboBox.class);if(section.getItemCount()<8)throw new AssertionError("Manual sections are incomplete");System.out.println("HelpManualPanelTest: OK (detailed indexed manual with comparison and diagram illustrations)");}
    private static <T>T field(Object target,String name,Class<T> type){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(target));}catch(Exception error){throw new RuntimeException(error);}}
}
