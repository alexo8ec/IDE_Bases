import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Render the repository's vector mark for native desktop/Android launchers. */
public class RenderBrandIcon {
    public static void main(String[] args)throws Exception{
        var icon=new FlatSVGIcon(Path.of(args[0]).toUri().toURL()).derive(512,512);
        if(!icon.hasFound())throw new IllegalStateException("Cannot load the brand vector");
        var image=new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();try{icon.paintIcon(null,g,0,0);}finally{g.dispose();}
        ImageIO.write(image,"png",Path.of(args[1]).toFile());
    }
}
