package com.nexodb.reporting;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** PDF pages use the exact Java2D preview at 144 dpi, preserving arbitrary Unicode and fonts. */
final class ReportPdfWriter {
    private ReportPdfWriter() {}
    static void write(ReportLayout layout,Path path) throws IOException {
        int pages=layout.pages().size(),objects=2+pages*3;long[] offsets=new long[objects+1];
        try(var stream=new CountingStream(new BufferedOutputStream(Files.newOutputStream(path)))) {
            stream.text("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");
            object(stream,offsets,1,"<< /Type /Catalog /Pages 2 0 R >>");
            StringBuilder kids=new StringBuilder();for(int i=0;i<pages;i++)kids.append(3+i*3).append(" 0 R ");
            object(stream,offsets,2,"<< /Type /Pages /Count "+pages+" /Kids [ "+kids+"] >>");
            for(int i=0;i<pages;i++) {
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Exportación PDF cancelada.");
                int page=3+i*3,image=page+1,content=page+2,w=layout.plan().design().pageWidth(),h=layout.plan().design().pageHeight();
                object(stream,offsets,page,"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 "+w+" "+h+"] /Resources << /XObject << /Im0 "+image+" 0 R >> >> /Contents "+content+" 0 R >>");
                var bitmap=ReportRenderer.image(layout,i,2);var jpeg=new ByteArrayOutputStream();
                ImageWriter writer=ImageIO.getImageWritersByFormatName("jpeg").next();
                try(var output=new MemoryCacheImageOutputStream(jpeg)) {
                    writer.setOutput(output);ImageWriteParam params=writer.getDefaultWriteParam();params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);params.setCompressionQuality(.94f);
                    writer.write(null,new IIOImage(bitmap,null,null),params);
                }finally{writer.dispose();bitmap.flush();}
                byte[] bytes=jpeg.toByteArray();offsets[image]=stream.count;stream.text(image+" 0 obj\n<< /Type /XObject /Subtype /Image /Width "+(w*2)+" /Height "+(h*2)+" /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length "+bytes.length+" >>\nstream\n");stream.write(bytes);stream.text("\nendstream\nendobj\n");
                String commands="q "+w+" 0 0 "+h+" 0 0 cm /Im0 Do Q\n";
                object(stream,offsets,content,"<< /Length "+commands.length()+" >>\nstream\n"+commands+"endstream");
            }
            long xref=stream.count;stream.text("xref\n0 "+(objects+1)+"\n0000000000 65535 f \n");
            for(int i=1;i<=objects;i++)stream.text(String.format(java.util.Locale.ROOT,"%010d 00000 n \n",offsets[i]));
            stream.text("trailer\n<< /Size "+(objects+1)+" /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n");
        }
    }
    private static void object(CountingStream stream,long[] offsets,int id,String contents) throws IOException {offsets[id]=stream.count;stream.text(id+" 0 obj\n"+contents+"\nendobj\n");}
    private static final class CountingStream extends OutputStream {
        private final OutputStream output;long count;
        CountingStream(OutputStream output){this.output=output;}
        void text(String text)throws IOException{write(text.getBytes(StandardCharsets.ISO_8859_1));}
        @Override public void write(int b)throws IOException{output.write(b);count++;}
        @Override public void write(byte[] b,int off,int len)throws IOException{output.write(b,off,len);count+=len;}
        @Override public void close()throws IOException{output.close();}
    }
}
