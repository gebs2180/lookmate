package com.lookmate.app.stylists;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;

/** Small photos for tests, with or without the metadata a phone camera writes. */
final class TestImages {

    static final String GPS_SECRET = "GPS 39.9612N 82.9988W";

    private TestImages() {
    }

    /** A plain JPEG; {@code seed} changes the pixels, so two seeds are two different photos. */
    static byte[] jpeg(int seed) {
        return encode(image(seed), "jpg");
    }

    static byte[] png(int seed) {
        return encode(image(seed), "png");
    }

    /** A JPEG as a phone writes it: EXIF with orientation 6, a maker, a capture time and a GPS block, and a comment. */
    static byte[] phoneJpeg(int seed) {
        byte[] plain = jpeg(seed);
        return withSegments(plain, cameraExif(), segment(0xFE, ("taken at home " + GPS_SECRET).getBytes(StandardCharsets.ISO_8859_1)));
    }

    static BufferedImage image(int seed) {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(Color.HSBtoRGB((seed % 12) / 12f, 0.6f, 0.9f)));
        g.fillRect(0, 0, 64, 48);
        g.setColor(Color.BLUE);
        g.fillOval(4 + seed % 20, 8, 30, 20);
        g.dispose();
        return img;
    }

    private static byte[] encode(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, format, out);
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The JPEG with these segments after its JFIF header, as a phone camera writes them. */
    static byte[] withSegments(byte[] jpeg, byte[]... segments) {
        int jfifEnd = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, jfifEnd);
        for (byte[] s : segments) {
            out.writeBytes(s);
        }
        out.write(jpeg, jfifEnd, jpeg.length - jfifEnd);
        return out.toByteArray();
    }

    /**
     * APP1 Exif as a phone writes it: IFD0 with the maker, orientation 6 and pointers to an Exif IFD (DateTimeOriginal)
     * and a GPS IFD (GPSDateStamp, standing in for the coordinates). From pokemans' ExactCopyTest.
     */
    static byte[] cameraExif() {
        ByteBuffer t = ByteBuffer.allocate(140);
        t.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        t.putShort((short) 4);                                                                     // IFD0 at 8
        t.putShort((short) 0x010F).putShort((short) 2).putInt(11).putInt(62);                      // Make
        t.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) 6).putShort((short) 0);
        t.putShort((short) 0x8769).putShort((short) 4).putInt(1).putInt(73);                      // Exif IFD
        t.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(111);                     // GPS IFD
        t.putInt(0);
        t.put("PhoneMaker\0".getBytes(StandardCharsets.ISO_8859_1));                             // 62..73
        t.putShort((short) 1);                                                                     // Exif IFD at 73
        t.putShort((short) 0x9003).putShort((short) 2).putInt(20).putInt(91);
        t.putInt(0);
        t.put("2026:10:30 14:05:09\0".getBytes(StandardCharsets.ISO_8859_1));                    // 91..111
        t.putShort((short) 1);                                                                     // GPS IFD at 111
        t.putShort((short) 0x001D).putShort((short) 2).putInt(11).putInt(129);
        t.putInt(0);
        t.put("2026:11:01\0".getBytes(StandardCharsets.ISO_8859_1));                             // 129..140
        ByteBuffer body = ByteBuffer.allocate(6 + 140).put("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1)).put(t.array());
        return segment(0xE1, body.array());
    }

    static byte[] segment(int marker, byte[] body) {
        return ByteBuffer.allocate(4 + body.length).put((byte) 0xFF).put((byte) marker).putShort((short) (body.length + 2)).put(body).array();
    }
}
