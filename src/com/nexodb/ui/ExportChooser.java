package com.nexodb.ui;

import static com.nexodb.util.I18n.tr;

import com.nexodb.util.DataExporter;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ExportChooser {
    public record Request(Path path, DataExporter.Format format) {}
    private ExportChooser() {}

    public static Request choose(Component owner, String baseName) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(tr("Exportar datos"));
        chooser.setAcceptAllFileFilterUsed(false);
        Map<javax.swing.filechooser.FileFilter, DataExporter.Format> formats = new LinkedHashMap<>();
        for (DataExporter.Format format : DataExporter.Format.values()) {
            FileNameExtensionFilter filter = new FileNameExtensionFilter(format.toString() + " (*." + format.extension() + ")", format.extension());
            formats.put(filter, format);
            chooser.addChoosableFileFilter(filter);
        }
        chooser.setFileFilter(formats.keySet().iterator().next());
        chooser.setSelectedFile(new File(clean(baseName) + ".csv"));
        chooser.addPropertyChangeListener(JFileChooser.FILE_FILTER_CHANGED_PROPERTY, event -> {
            DataExporter.Format selected = formats.get(chooser.getFileFilter());
            if (selected == null) return;
            String name = stripExtension(chooser.getSelectedFile() == null ? clean(baseName) : chooser.getSelectedFile().getName());
            chooser.setSelectedFile(new File(name + "." + selected.extension()));
        });
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return null;
        DataExporter.Format format = formats.get(chooser.getFileFilter());
        if (format == null) format = DataExporter.Format.CSV;
        File selected = chooser.getSelectedFile();
        String expected = "." + format.extension();
        if (!selected.getName().toLowerCase().endsWith(expected)) selected = new File(selected.getParentFile(), selected.getName() + expected);
        if (selected.exists()) {
            int replace = JOptionPane.showConfirmDialog(owner, tr("El archivo ya existe. ¿Deseas reemplazarlo?"), tr("Reemplazar archivo"),
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (replace != JOptionPane.YES_OPTION) return null;
        }
        return new Request(selected.toPath(), format);
    }

    private static String clean(String name) {
        String value = name == null || name.isBlank() ? "resultado" : name.replaceAll("[^a-zA-Z0-9._-]+", "_");
        return stripExtension(value);
    }
    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
