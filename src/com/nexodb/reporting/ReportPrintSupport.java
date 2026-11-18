package com.nexodb.reporting;

import java.awt.print.PageFormat;
import java.awt.print.PrinterJob;
import javax.print.attribute.PrintRequestAttributeSet;
import javax.print.attribute.HashPrintRequestAttributeSet;
import javax.print.attribute.standard.MediaSizeName;
import javax.print.attribute.standard.OrientationRequested;

/** Keeps the physical printer page in sync with the report preview. */
public final class ReportPrintSupport {
    private ReportPrintSupport() {}

    public static PrintRequestAttributeSet attributes(ReportDesign design) {
        var attributes=new HashPrintRequestAttributeSet();
        attributes.add(MediaSizeName.ISO_A4);
        attributes.add(design.landscape()?OrientationRequested.LANDSCAPE:OrientationRequested.PORTRAIT);
        return attributes;
    }

    public static PageFormat pageFormat(PrinterJob job,PrintRequestAttributeSet attributes) {
        return job.validatePage(job.getPageFormat(attributes));
    }
}
