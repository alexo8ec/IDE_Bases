package com.nexodb.ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Types;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;
import static com.nexodb.util.I18n.tr;

/** Large text and calendar editors used by editable query results. */
final class ResultCellEditorDialog {
    enum Kind { PLAIN, TEXT, DATE, TIME, TIMESTAMP }
    record Outcome(boolean accepted,Object value) {}

    private ResultCellEditorDialog() {}


    private static Component dialogOwner(Component parent) {
        Window owner=parent instanceof Window window?window:SwingUtilities.getWindowAncestor(parent);
        return owner==null?parent:owner;
    }
    static Kind kind(int jdbcType,Object value) {
        return switch (jdbcType) {
            case Types.LONGVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB,Types.SQLXML -> Kind.TEXT;
            case Types.DATE -> Kind.DATE;
            case Types.TIME -> Kind.TIME;
            case Types.TIMESTAMP -> Kind.TIMESTAMP;
            default -> value instanceof String text&&(text.length()>255||text.indexOf('\n')>=0||text.indexOf('\r')>=0)
                    ?Kind.TEXT:Kind.PLAIN;
        };
    }

    static Outcome show(Component parent,String column,Object value,int jdbcType,boolean editable) {
        Kind kind=kind(jdbcType,value);
        return switch(kind) {
            case TEXT -> showText(parent,column,value,editable);
            case DATE,TIME,TIMESTAMP -> showTemporal(parent,column,value,kind,editable);
            default -> new Outcome(false,value);
        };
    }

    private static Outcome showText(Component parent,String column,Object value,boolean editable) {
        JTextArea text=new JTextArea(value==null?"":String.valueOf(value),24,90);
        text.setName("result-long-text");text.setEditable(editable);text.setLineWrap(false);text.setTabSize(4);text.setCaretPosition(0);
        JScrollPane scroll=new JScrollPane(text);scroll.setPreferredSize(new Dimension(850,500));
        JCheckBox setNull=new JCheckBox(tr("Establecer NULL"),value==null);setNull.setEnabled(editable);
        JLabel size=new JLabel();Runnable update=()->size.setText(tr("Tamaño: ")+text.getText().length()+tr(" caracteres"));update.run();
        text.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){update.run();}public void removeUpdate(DocumentEvent e){update.run();}public void changedUpdate(DocumentEvent e){update.run();}});
        setNull.addActionListener(e->text.setEnabled(!setNull.isSelected()));if(setNull.isSelected())text.setEnabled(false);
        JButton copy=UiLanguage.button("Copiar todo"),save=UiLanguage.button("Guardar en archivo…"),load=UiLanguage.button("Cargar desde archivo…");
        copy.addActionListener(e->Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text.getText()),null));
        save.addActionListener(e->chooseFile(parent,false,text));load.addActionListener(e->chooseFile(parent,true,text));load.setVisible(editable);
        JPanel tools=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));tools.add(setNull);tools.add(copy);tools.add(load);tools.add(save);tools.add(size);
        JPanel content=new JPanel(new BorderLayout(6,6));content.add(tools,BorderLayout.NORTH);content.add(scroll);
        int answer=JOptionPane.showConfirmDialog(dialogOwner(parent),content,(editable?tr("Editar contenido · "):tr("Ver contenido · "))+column,
                JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE);
        if(answer!=JOptionPane.OK_OPTION)return new Outcome(false,value);
        return new Outcome(true,editable?(setNull.isSelected()?null:text.getText()):value);
    }

    private static void chooseFile(Component parent,boolean load,JTextArea text) {
        JFileChooser chooser=new JFileChooser();
        if(chooser.showDialog(parent,tr(load?"Cargar":"Guardar"))!=JFileChooser.APPROVE_OPTION)return;
        try {
            if(load)text.setText(Files.readString(chooser.getSelectedFile().toPath(),StandardCharsets.UTF_8));
            else Files.writeString(chooser.getSelectedFile().toPath(),text.getText(),StandardCharsets.UTF_8);
        }catch(IOException error){JOptionPane.showMessageDialog(parent,error.getMessage(),tr("No se pudo abrir el archivo"),JOptionPane.ERROR_MESSAGE);}
    }

    private static Outcome showTemporal(Component parent,String column,Object value,Kind kind,boolean editable) {
        TemporalPanel picker=new TemporalPanel(kind,value);picker.setEnabledDeep(editable);
        JCheckBox setNull=new JCheckBox(tr("Establecer NULL"),value==null);setNull.setEnabled(editable);
        setNull.addActionListener(e->picker.setEnabledDeep(editable&&!setNull.isSelected()));
        if(setNull.isSelected())picker.setEnabledDeep(false);
        JPanel content=new JPanel(new BorderLayout(6,6));content.add(setNull,BorderLayout.NORTH);content.add(picker);
        int answer=JOptionPane.showConfirmDialog(dialogOwner(parent),content,(editable?tr("Editar fecha y hora · "):tr("Ver fecha y hora · "))+column,
                JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE);
        if(answer!=JOptionPane.OK_OPTION)return new Outcome(false,value);
        return new Outcome(true,editable?(setNull.isSelected()?null:picker.value()):value);
    }

    static String normalizeTemporal(Kind kind,Object value) {
        TemporalValue parsed=TemporalValue.parse(kind,value);
        return parsed.format(kind);
    }

    private static final class TemporalPanel extends JPanel {
        private final Kind kind;private LocalDate selectedDate;private YearMonth visibleMonth;
        private final JLabel month=new JLabel("",SwingConstants.CENTER);private final JPanel days=new JPanel(new GridLayout(0,7,3,3));
        private final JButton previous=new JButton("‹"),next=new JButton("›"),now=UiLanguage.button("Ahora");
        private final JSpinner time;

        TemporalPanel(Kind kind,Object value) {
            super(new BorderLayout(6,6));this.kind=kind;TemporalValue parsed=TemporalValue.parse(kind,value);selectedDate=parsed.date;visibleMonth=YearMonth.from(selectedDate);
            Date initial=Date.from(LocalDateTime.of(LocalDate.now(),parsed.time).atZone(ZoneId.systemDefault()).toInstant());
            time=new JSpinner(new SpinnerDateModel(initial,null,null,java.util.Calendar.SECOND));time.setEditor(new JSpinner.DateEditor(time,"HH:mm:ss"));time.setName("result-time-picker");
            if(kind!=Kind.TIME){
                JPanel heading=new JPanel(new BorderLayout());heading.add(previous,BorderLayout.WEST);heading.add(month);heading.add(next,BorderLayout.EAST);add(heading,BorderLayout.NORTH);add(days);
                previous.addActionListener(e->{visibleMonth=visibleMonth.minusMonths(1);renderDays();});next.addActionListener(e->{visibleMonth=visibleMonth.plusMonths(1);renderDays();});renderDays();
            }
            JPanel footer=new JPanel(new FlowLayout(FlowLayout.LEFT));if(kind!=Kind.DATE){footer.add(UiLanguage.label("Hora:"));footer.add(time);}footer.add(now);add(footer,BorderLayout.SOUTH);
            now.addActionListener(e->{LocalDateTime current=LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);selectedDate=current.toLocalDate();visibleMonth=YearMonth.from(selectedDate);time.setValue(Date.from(current.atZone(ZoneId.systemDefault()).toInstant()));renderDays();});
            setPreferredSize(new Dimension(kind==Kind.TIME?320:430,kind==Kind.TIME?90:330));
        }

        private void renderDays() {
            if(kind==Kind.TIME)return;days.removeAll();month.setText(visibleMonth.getMonth().getDisplayName(java.time.format.TextStyle.FULL,new Locale("es"))+" "+visibleMonth.getYear());
            for(String name:new String[]{"lun","mar","mié","jue","vie","sáb","dom"}){JLabel label=new JLabel(name,SwingConstants.CENTER);label.setFont(label.getFont().deriveFont(Font.BOLD));days.add(label);}
            int blanks=visibleMonth.atDay(1).getDayOfWeek().getValue()-1;for(int i=0;i<blanks;i++)days.add(new JLabel());
            for(int day=1;day<=visibleMonth.lengthOfMonth();day++){
                LocalDate date=visibleMonth.atDay(day);JButton button=new JButton(String.valueOf(day));button.setMargin(new Insets(3,3,3,3));button.putClientProperty("JButton.buttonType",date.equals(selectedDate)?"default":null);
                button.addActionListener(e->{selectedDate=date;renderDays();});days.add(button);
            }
            days.revalidate();days.repaint();
        }

        String value() {return new TemporalValue(selectedDate,spinnerTime()).format(kind);}
        private LocalTime spinnerTime(){return ((Date)time.getValue()).toInstant().atZone(ZoneId.systemDefault()).toLocalTime().truncatedTo(ChronoUnit.SECONDS);}
        void setEnabledDeep(boolean enabled){setEnabled(enabled);previous.setEnabled(enabled);next.setEnabled(enabled);now.setEnabled(enabled);time.setEnabled(enabled);for(Component component:days.getComponents())component.setEnabled(enabled);}
    }

    private record TemporalValue(LocalDate date,LocalTime time) {
        static TemporalValue parse(Kind kind,Object value) {
            LocalDate date=LocalDate.now();LocalTime time=LocalTime.now().truncatedTo(ChronoUnit.SECONDS);String text=value==null?"":String.valueOf(value).trim();
            try {
                if(kind==Kind.DATE&&text.length()>=10)date=LocalDate.parse(text.substring(0,10));
                else if(kind==Kind.TIME&&!text.isBlank())time=parseTime(text);
                else if(kind==Kind.TIMESTAMP&&text.length()>=10){date=LocalDate.parse(text.substring(0,10));if(text.length()>10)time=parseTime(text.substring(10).trim());}
            }catch(DateTimeParseException|IndexOutOfBoundsException ignored){}
            return new TemporalValue(date,time);
        }
        private static LocalTime parseTime(String text){return LocalTime.parse(text);}
        String format(Kind kind){return switch(kind){case DATE->date.format(DateTimeFormatter.ISO_LOCAL_DATE);case TIME->time.format(DateTimeFormatter.ofPattern("HH:mm:ss"));default->date.format(DateTimeFormatter.ISO_LOCAL_DATE)+" "+time.format(DateTimeFormatter.ofPattern("HH:mm:ss"));};}
    }
}
