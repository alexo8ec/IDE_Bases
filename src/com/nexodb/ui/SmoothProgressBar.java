package com.nexodb.ui;

import javax.swing.*;
import java.awt.*;

/** Time-based indeterminate progress that resumes at the current phase after a busy UI frame. */
final class SmoothProgressBar extends JProgressBar {
    private final Timer frames=new Timer(25,event->repaint());
    SmoothProgressBar(){super();frames.setCoalesce(true);}
    @Override public void addNotify(){super.addNotify();sync();}
    @Override public void removeNotify(){frames.stop();super.removeNotify();}
    @Override public void setVisible(boolean visible){super.setVisible(visible);sync();}
    @Override public void setIndeterminate(boolean value){super.setIndeterminate(value);sync();}
    private void sync(){if(frames==null)return;if(isDisplayable()&&isVisible()&&isIndeterminate())frames.start();else frames.stop();}
    @Override protected void paintComponent(Graphics graphics){
        if(!isIndeterminate()){super.paintComponent(graphics);return;}
        Graphics2D g=(Graphics2D)graphics.create();try{
            int width=getWidth(),height=getHeight(),arc=Math.max(4,height);Color background=UIManager.getColor("ProgressBar.background"),foreground=UIManager.getColor("ProgressBar.foreground");
            if(background==null)background=getBackground();if(foreground==null)foreground=getForeground();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(background);g.fillRoundRect(0,0,width,height,arc,arc);
            int segment=Math.max(24,Math.min(width/3,70));long cycle=1_150_000_000L;double phase=(System.nanoTime()%cycle)/(double)cycle;double wave=phase<.5?phase*2:(1-phase)*2;int x=(int)Math.round(wave*Math.max(0,width-segment));
            g.setColor(foreground);g.fillRoundRect(x,0,segment,height,arc,arc);
        }finally{g.dispose();}
    }
}
