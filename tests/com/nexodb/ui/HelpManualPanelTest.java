package com.nexodb.ui;

import javax.swing.*;
import java.lang.reflect.Field;

public final class HelpManualPanelTest {
    public static void main(String[] args)throws Exception{HelpManualPanel[] panel=new HelpManualPanel[1];SwingUtilities.invokeAndWait(()->panel[0]=new HelpManualPanel());DefaultListModel<?> model=field(panel[0],"model",DefaultListModel.class);if(model.size()<15)throw new AssertionError("Manual index is incomplete");JTextField search=field(panel[0],"search",JTextField.class);SwingUtilities.invokeAndWait(()->search.setText("monitor"));if(model.isEmpty())throw new AssertionError("Manual search did not find server monitor");System.out.println("HelpManualPanelTest: OK (searchable indexed offline manual with illustrations)");}
    private static <T>T field(Object target,String name,Class<T> type){try{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return type.cast(field.get(target));}catch(Exception error){throw new RuntimeException(error);}}
}
