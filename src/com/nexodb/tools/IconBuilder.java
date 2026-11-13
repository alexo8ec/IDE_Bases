package com.nexodb.tools;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class IconBuilder {
    private IconBuilder() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Uso: IconBuilder origen.png destino.ico");
        BufferedImage source = ImageIO.read(Path.of(args[0]).toFile());
        if (source == null) throw new IOException("No se pudo leer el PNG del icono.");
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        List<byte[]> images = new ArrayList<>();
        for (int size : sizes) images.add(toPng(scale(source, size)));
        writeIco(images, sizes, Path.of(args[1]));
    }

    private static BufferedImage scale(BufferedImage source, int size) {
        BufferedImage target = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.drawImage(source, 0, 0, size, size, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IOException("No hay codificador PNG disponible.");
        return output.toByteArray();
    }

    private static void writeIco(List<byte[]> images, int[] sizes, Path destination) throws IOException {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(destination)))) {
            writeShortLE(output, 0);
            writeShortLE(output, 1);
            writeShortLE(output, images.size());
            int offset = 6 + images.size() * 16;
            for (int index = 0; index < images.size(); index++) {
                int size = sizes[index];
                output.writeByte(size == 256 ? 0 : size);
                output.writeByte(size == 256 ? 0 : size);
                output.writeByte(0);
                output.writeByte(0);
                writeShortLE(output, 1);
                writeShortLE(output, 32);
                writeIntLE(output, images.get(index).length);
                writeIntLE(output, offset);
                offset += images.get(index).length;
            }
            for (byte[] image : images) output.write(image);
        }
    }

    private static void writeShortLE(DataOutputStream output, int value) throws IOException {
        output.writeByte(value & 0xff);
        output.writeByte((value >>> 8) & 0xff);
    }

    private static void writeIntLE(DataOutputStream output, int value) throws IOException {
        output.writeByte(value & 0xff);
        output.writeByte((value >>> 8) & 0xff);
        output.writeByte((value >>> 16) & 0xff);
        output.writeByte((value >>> 24) & 0xff);
    }
}
