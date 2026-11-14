package com.nexodb.reporting.ui;

import java.awt.*;

/** Flow layout whose preferred height includes wrapped rows, suitable for resizable toolbars. */
public final class WrapLayout extends FlowLayout {
    public WrapLayout(){super(FlowLayout.LEFT,5,4);}
    @Override public Dimension preferredLayoutSize(Container target) {
        synchronized(target.getTreeLock()) {
            int available=target.getWidth();Container parent=target.getParent();if(available<=0&&parent!=null)available=parent.getWidth();if(available<=0)available=1000;
            Insets insets=target.getInsets();int limit=Math.max(1,available-insets.left-insets.right-2*getHgap());
            int width=0,height=getVgap(),rowWidth=0,rowHeight=0;
            for(Component child:target.getComponents())if(child.isVisible()) {
                Dimension size=child.getPreferredSize();int gap=rowWidth==0?0:getHgap();
                if(rowWidth>0&&rowWidth+gap+size.width>limit){width=Math.max(width,rowWidth);height+=rowHeight+getVgap();rowWidth=0;rowHeight=0;gap=0;}
                rowWidth+=gap+size.width;rowHeight=Math.max(rowHeight,size.height);
            }
            width=Math.max(width,rowWidth);height+=rowHeight+getVgap();return new Dimension(width+insets.left+insets.right+2*getHgap(),height+insets.top+insets.bottom);
        }
    }
    @Override public Dimension minimumLayoutSize(Container target){return new Dimension(150,preferredLayoutSize(target).height);}
}
