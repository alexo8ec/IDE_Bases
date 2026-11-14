import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Export the selected SVG or raster master at the size required by native launchers. */
public class RenderBrandIcon {
    public static void main(String[] args)throws Exception{
        var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();try{
            if(args[0].toLowerCase(java.util.Locale.ROOT).endsWith(".svg")){
                var icon=new FlatSVGIcon(Path.of(args[0]).toUri().toURL()).derive(512,512);if(!icon.hasFound())throw new IllegalStateException("Cannot load the brand vector");icon.paintIcon(null,g,0,0);
            }else{
                var source=ImageIO.read(Path.of(args[0]).toFile());if(source==null)throw new IllegalStateException("Cannot load the brand master");g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(source,0,0,512,512,null);
            }
        }finally{g.dispose();}
        ImageIO.write(image,"png",Path.of(args[1]).toFile());
    }
}
