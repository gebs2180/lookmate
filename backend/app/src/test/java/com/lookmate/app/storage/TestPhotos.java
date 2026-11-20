package com.lookmate.app.storage;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/** Small photos with the metadata a phone writes: GPS position, the phone's make, and an orientation. */
public final class TestPhotos {

    public static final String MAKE = "Pixelphone";

    private TestPhotos() {
    }

    public static BufferedImage image() {
        BufferedImage image = new BufferedImage(48, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(120, 70, 40));
        g.fillRect(0, 0, 48, 32);
        g.setColor(new Color(230, 200, 150));
        g.fillOval(10, 4, 24, 24);
        g.dispose();
        return image;
    }

    /** A JPEG with an EXIF block: Make, Orientation 6 and a GPS latitude. */
    public static byte[] jpegWithGps() throws IOException {
        byte[] jpeg = encode("jpeg");
        int app0End = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));   // FFD8, then FFE0 and its length
        byte[] tiff = tiffWithGps();
        ByteBuffer app1 = ByteBuffer.allocate(4 + 6 + tiff.length);
        app1.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (2 + 6 + tiff.length)).put("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1))
                .put(tiff);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, app0End);
        out.writeBytes(app1.array());
        out.write(jpeg, app0End, jpeg.length - app0End);
        return out.toByteArray();
    }

    /** A PNG with a text chunk naming a place. */
    public static byte[] pngWithText() throws IOException {
        byte[] png = encode("png");
        int ihdrEnd = 8 + 12 + 13;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, ihdrEnd);
        out.writeBytes(chunk("tEXt", "Comment\0Taken at 12 Elm St".getBytes(StandardCharsets.ISO_8859_1)));
        out.write(png, ihdrEnd, png.length - ihdrEnd);
        return out.toByteArray();
    }

    public static byte[] encode(String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image(), format, out);
        return out.toByteArray();
    }

    /**
     * Big-endian TIFF: IFD0 (Make, Orientation, GPS pointer) at 8, the GPS IFD (LatitudeRef, Latitude) at 50, the make
     * string at 80 and the latitude's three rationals at 92.
     */
    private static byte[] tiffWithGps() {
        ByteBuffer b = ByteBuffer.allocate(116);
        b.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        b.putShort((short) 3);
        b.putShort((short) 0x010F).putShort((short) 2).putInt(MAKE.length() + 1).putInt(80);
        b.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) 6).putShort((short) 0);
        b.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(50);
        b.putInt(0);
        b.putShort((short) 2);
        b.putShort((short) 0x0001).putShort((short) 2).putInt(2).put((byte) 'N').put((byte) 0).putShort((short) 0);
        b.putShort((short) 0x0002).putShort((short) 5).putInt(3).putInt(92);
        b.putInt(0);
        b.put(MAKE.getBytes(StandardCharsets.ISO_8859_1)).put((byte) 0).put((byte) 0);
        b.putInt(39).putInt(1).putInt(57).putInt(1).putInt(3000).putInt(100);
        return b.array();
    }

    private static byte[] chunk(String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(t).put(data).putInt((int) crc.getValue()).array();
    }
}
