package com.nexodb.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.nexodb.model.DatabaseType;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

public final class IconFactory {
    public enum Kind {
        ADD, CONNECT, DISCONNECT, REFRESH, PLAY, STOP, COMMIT, ROLLBACK, EXPORT,
        EDIT, DELETE, DATABASE, SCHEMA, TABLE, VIEW, COLUMN, SETTINGS, UPDATE,
        INFO, EXIT, SAVE, TEST, CANCEL, FOLDER, CONNECTIONS, OBJECTS,
        KEY, SQL, PROCEDURE, FUNCTION, TRIGGER, EVENT, COMPARE, USER, SEARCH,
        UNDO, REDO, COPY, IMPORT, BACKUP, TOOLS, DIAGRAM, FILTER, HISTORY
    }

    public static final Color BLUE = new Color(0x2563EB);
    public static final Color CYAN = new Color(0x0891B2);
    public static final Color GREEN = new Color(0x16A34A);
    public static final Color AMBER = new Color(0xD97706);
    public static final Color RED = new Color(0xDC2626);
    public static final Color SLATE = new Color(0x475569);

    private IconFactory() {}

    public static Icon of(Kind kind) { return of(kind, 18); }
    public static Icon of(Kind kind, int size) { return new VectorIcon(kind, size, color(kind)); }
    public static Icon of(Kind kind,int size,Color color){return new VectorIcon(kind,size,color);}
    public static Icon database(DatabaseType type, int size) {
        DatabaseType engine = type == null ? DatabaseType.GENERIC : type;
        if (engine == DatabaseType.GENERIC) return of(Kind.DATABASE, size);
        if (engine == DatabaseType.H2) {
            var resource = IconFactory.class.getResource("/com/nexodb/assets/engines/h2.png");
            return resource == null ? of(Kind.DATABASE, size) : new RasterIcon(new ImageIcon(resource).getImage(), size);
        }
        String file = switch (engine) {
            case POSTGRESQL -> "postgresql.svg";
            case MYSQL -> "mysql.svg";
            case MARIADB -> "mariadb.svg";
            case SQLSERVER -> "sqlserver.svg";
            case ORACLE -> "oracle.svg";
            case SQLITE -> "sqlite.svg";
            case MONGODB -> "mongodb.svg";
            default -> null;
        };
        if (file == null) return of(Kind.DATABASE, size);
        return new FlatSVGIcon("com/nexodb/assets/engines/" + file, size, size);
    }

    private static Color color(Kind kind) {
        return switch (kind) {
            case ADD, CONNECT, PLAY, SAVE, CONNECTIONS, USER, SEARCH -> BLUE;
            case REFRESH, UPDATE, EXPORT, OBJECTS, COMPARE -> CYAN;
            case COMMIT, TEST, PROCEDURE -> GREEN;
            case ROLLBACK, EDIT, FOLDER -> AMBER;
            case DISCONNECT, STOP, DELETE, CANCEL, EXIT -> RED;
            default -> SLATE;
        };
    }

    private record RasterIcon(Image image, int size) implements Icon {
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                int sourceWidth = Math.max(1, image.getWidth(component));
                int sourceHeight = Math.max(1, image.getHeight(component));
                double scale = Math.min((double) size / sourceWidth, (double) size / sourceHeight);
                int width = Math.max(1, (int) Math.round(sourceWidth * scale));
                int height = Math.max(1, (int) Math.round(sourceHeight * scale));
                g.drawImage(image, x + (size - width) / 2, y + (size - height) / 2, width, height, component);
            } finally {
                g.dispose();
            }
        }
    }

    private record VectorIcon(Kind kind, int size, Color color) implements Icon {
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                double scale = size / 18.0;
                g.scale(scale, scale);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                Color visible = color;
                if (com.formdev.flatlaf.FlatLaf.isLafDark()) {
                    visible = new Color(Math.min(255, color.getRed() + 65), Math.min(255, color.getGreen() + 65), Math.min(255, color.getBlue() + 45));
                }
                g.setColor(component != null && !component.isEnabled() ? UIManager.getColor("Label.disabledForeground") : visible);
                g.setStroke(new BasicStroke(1.65f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                paint(g);
            } finally {
                g.dispose();
            }
        }

        private void paint(Graphics2D g) {
            switch (kind) {
                case ADD -> {
                    g.draw(new Ellipse2D.Double(2, 2, 14, 14));
                    line(g, 9, 5.5, 9, 12.5); line(g, 5.5, 9, 12.5, 9);
                }
                case CONNECT -> {
                    g.draw(new Ellipse2D.Double(2, 5, 6, 6)); g.draw(new Ellipse2D.Double(10, 5, 6, 6));
                    line(g, 7.8, 8, 10.2, 8); line(g, 9, 3, 9, 13);
                }
                case DISCONNECT -> {
                    g.draw(new Ellipse2D.Double(2, 5, 5, 5)); g.draw(new Ellipse2D.Double(11, 5, 5, 5));
                    line(g, 3, 15, 15, 3);
                }
                case REFRESH, UPDATE -> {
                    g.draw(new Arc2D.Double(2.5, 2.5, 13, 13, 35, 275, Arc2D.OPEN));
                    Path2D arrow = new Path2D.Double();
                    arrow.moveTo(12.8, 2.8); arrow.lineTo(15.5, 3.1); arrow.lineTo(14.9, 5.7); g.draw(arrow);
                    if (kind == Kind.UPDATE) { line(g, 9, 6, 9, 12); line(g, 6.8, 9.8, 9, 12); line(g, 11.2, 9.8, 9, 12); }
                }
                case PLAY -> { Path2D p = new Path2D.Double(); p.moveTo(5, 3); p.lineTo(14.5, 9); p.lineTo(5, 15); p.closePath(); g.fill(p); }
                case STOP -> g.fill(new RoundRectangle2D.Double(4, 4, 10, 10, 2, 2));
                case IMPORT -> {g.draw(new RoundRectangle2D.Double(3,10.5,12,5,1.5,1.5));line(g,9,11,9,2.5);line(g,5.8,5.7,9,2.5);line(g,12.2,5.7,9,2.5);}
                case UNDO, REDO -> {Graphics2D draw=(Graphics2D)g.create();if(kind==Kind.REDO){draw.translate(18,0);draw.scale(-1,1);}draw.draw(new Arc2D.Double(3,5,12,10,0,180,Arc2D.OPEN));line(draw,3,10,3,4);line(draw,3,4,8,4);draw.dispose();}
                case COPY -> {g.draw(new RoundRectangle2D.Double(6,5,9,11,1.5,1.5));line(g,3,12,3,2);line(g,3,2,12,2);}
                case BACKUP -> {paintDatabase(g,false);g.setColor(ThemeManager.color(0xF8FAFC,0x26323E));g.fill(new Ellipse2D.Double(9,9,8,8));g.setColor(ThemeManager.color(0x167567,0x62CEB0));g.draw(new Ellipse2D.Double(9.5,9.5,7,7));line(g,13,11,13,13);line(g,13,13,15,14);}
                case TOOLS -> {g.draw(new RoundRectangle2D.Double(2,6,14,10,2,2));g.draw(new RoundRectangle2D.Double(6,2,6,4,1,1));line(g,2,10,16,10);line(g,7,10,7,12);line(g,11,10,11,12);}
                case DIAGRAM -> {g.draw(new RoundRectangle2D.Double(1.5,2.5,6,5,1,1));g.draw(new RoundRectangle2D.Double(10.5,10.5,6,5,1,1));line(g,4.5,7.5,4.5,13);line(g,4.5,13,10.5,13);line(g,1.5,4.5,7.5,4.5);line(g,10.5,12.5,16.5,12.5);}
                case FILTER -> {Path2D p=new Path2D.Double();p.moveTo(2,3);p.lineTo(16,3);p.lineTo(11,9);p.lineTo(11,14);p.lineTo(7,16);p.lineTo(7,9);p.closePath();g.draw(p);}
                case HISTORY -> {g.draw(new Arc2D.Double(3,2.5,13,13,-25,295,Arc2D.OPEN));line(g,2,3,2,8);line(g,2,8,7,8);line(g,9.5,5,9.5,9.5);line(g,9.5,9.5,12.5,11);}
                case COMMIT -> { line(g, 3, 9, 7, 13); line(g, 7, 13, 15, 4); }
                case ROLLBACK -> {
                    g.draw(new Arc2D.Double(3, 3, 12, 12, 70, 245, Arc2D.OPEN));
                    line(g, 3.2, 5.5, 3, 10); line(g, 3, 10, 7.2, 9.2);
                }
                case EXPORT -> {
                    g.draw(new RoundRectangle2D.Double(3, 10.5, 12, 5, 1.5, 1.5));
                    line(g, 9, 2.5, 9, 11); line(g, 5.8, 7.8, 9, 11); line(g, 12.2, 7.8, 9, 11);
                }
                case EDIT -> {
                    line(g, 4, 14, 13.7, 4.3); line(g, 6.2, 15, 15, 6.2);
                    line(g, 4, 14, 3, 16); line(g, 3, 16, 6.2, 15);
                }
                case DELETE -> {
                    g.draw(new RoundRectangle2D.Double(4.5, 5, 9, 10.5, 1.5, 1.5));
                    line(g, 3.5, 5, 14.5, 5); line(g, 6.5, 2.8, 11.5, 2.8); line(g, 7.5, 8, 7.5, 13); line(g, 10.5, 8, 10.5, 13);
                }
                case DATABASE, SCHEMA -> paintDatabase(g, kind == Kind.SCHEMA);
                case TABLE -> {
                    g.draw(new RoundRectangle2D.Double(2.5, 3, 13, 12, 1.5, 1.5));
                    line(g, 2.5, 7, 15.5, 7); line(g, 7, 3, 7, 15); line(g, 11, 3, 11, 15); line(g, 2.5, 11, 15.5, 11);
                }
                case VIEW -> {
                    Path2D eye = new Path2D.Double(); eye.moveTo(2, 9); eye.curveTo(5, 4, 13, 4, 16, 9); eye.curveTo(13, 14, 5, 14, 2, 9); g.draw(eye);
                    g.fill(new Ellipse2D.Double(7, 7, 4, 4));
                }
                case COLUMN -> { g.draw(new Rectangle2D.Double(3, 3, 4, 12)); g.draw(new Rectangle2D.Double(11, 3, 4, 12)); }
                case KEY -> { g.draw(new Ellipse2D.Double(2.5, 3, 7, 7)); line(g, 8, 8.5, 15.5, 16); line(g, 12, 12.5, 14, 10.5); }
                case SQL -> { line(g, 3, 4, 15, 4); line(g, 3, 9, 12, 9); line(g, 3, 14, 15, 14); }
                case PROCEDURE -> { g.draw(new RoundRectangle2D.Double(2.5, 3, 13, 12, 2, 2)); line(g, 6, 6.5, 12, 6.5); line(g, 6, 10, 12, 10); }
                case FUNCTION -> { line(g, 3, 14, 7, 4); line(g, 5, 9, 12, 9); line(g, 10, 4, 15, 4); }
                case TRIGGER -> { Path2D p = new Path2D.Double(); p.moveTo(10, 1.5); p.lineTo(4.5, 10); p.lineTo(8.7, 10); p.lineTo(7.5, 16.5); p.lineTo(14, 7.5); p.lineTo(9.8, 7.5); p.closePath(); g.fill(p); }
                case EVENT -> { g.draw(new Ellipse2D.Double(2.5, 2.5, 13, 13)); line(g, 9, 5, 9, 9); line(g, 9, 9, 12, 11); }
                case COMPARE -> { line(g, 3, 6, 14, 6); line(g, 11, 3, 14, 6); line(g, 11, 9, 14, 6); line(g, 15, 12, 4, 12); line(g, 7, 9, 4, 12); line(g, 7, 15, 4, 12); }
                case USER -> { g.draw(new Ellipse2D.Double(6, 2.5, 6, 6)); g.draw(new Arc2D.Double(3, 8, 12, 8, 0, 180, Arc2D.OPEN)); }
                case SEARCH -> { g.draw(new Ellipse2D.Double(2.5, 2.5, 9, 9)); line(g, 10, 10, 16, 16); }
                case SETTINGS -> {
                    g.draw(new Ellipse2D.Double(5, 5, 8, 8)); g.draw(new Ellipse2D.Double(7.3, 7.3, 3.4, 3.4));
                    for (int angle = 0; angle < 360; angle += 45) {
                        double a = Math.toRadians(angle); line(g, 9 + Math.cos(a)*5, 9 + Math.sin(a)*5, 9 + Math.cos(a)*7, 9 + Math.sin(a)*7);
                    }
                }
                case INFO -> { g.draw(new Ellipse2D.Double(2.5, 2.5, 13, 13)); g.fill(new Ellipse2D.Double(8.2, 5, 1.6, 1.6)); line(g, 9, 8, 9, 13); }
                case EXIT -> { g.draw(new RoundRectangle2D.Double(2.5, 3, 8, 12, 1.5, 1.5)); line(g, 7, 9, 16, 9); line(g, 13, 6, 16, 9); line(g, 13, 12, 16, 9); }
                case SAVE -> { g.draw(new RoundRectangle2D.Double(3, 2.5, 12, 13, 1.5, 1.5)); g.drawRect(6, 3, 6, 4); g.draw(new Ellipse2D.Double(6.5, 10, 5, 5)); }
                case TEST -> { Path2D bolt = new Path2D.Double(); bolt.moveTo(10, 1.5); bolt.lineTo(4.5, 10); bolt.lineTo(8.7, 10); bolt.lineTo(7.5, 16.5); bolt.lineTo(14, 7.5); bolt.lineTo(9.8, 7.5); bolt.closePath(); g.fill(bolt); }
                case CANCEL -> { line(g, 4, 4, 14, 14); line(g, 14, 4, 4, 14); }
                case FOLDER -> { Path2D p = new Path2D.Double(); p.moveTo(2, 5); p.lineTo(7, 5); p.lineTo(8.5, 7); p.lineTo(16, 7); p.lineTo(15, 15); p.lineTo(2, 15); p.closePath(); g.draw(p); }
                case CONNECTIONS -> { paintDatabase(g, false); g.fill(new Ellipse2D.Double(12, 11, 5, 5)); }
                case OBJECTS -> { g.draw(new Rectangle2D.Double(2.5, 3, 5, 5)); g.draw(new Rectangle2D.Double(10.5, 10, 5, 5)); line(g, 7.5, 5.5, 11, 5.5); line(g, 11, 5.5, 11, 10); }
            }
        }

        private void paintDatabase(Graphics2D g, boolean smallMark) {
            g.draw(new Ellipse2D.Double(3, 2.5, 12, 5));
            g.draw(new Arc2D.Double(3, 6, 12, 5, 180, 180, Arc2D.OPEN));
            g.draw(new Arc2D.Double(3, 10, 12, 5, 180, 180, Arc2D.OPEN));
            line(g, 3, 5, 3, 12.5); line(g, 15, 5, 15, 12.5);
            if (smallMark) g.fill(new Ellipse2D.Double(12.5, 12.5, 4, 4));
        }

        private static void line(Graphics2D g, double x1, double y1, double x2, double y2) {
            g.draw(new Line2D.Double(x1, y1, x2, y2));
        }
    }
}

