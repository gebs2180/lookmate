package com.lookmate.app.stylists;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * A portfolio photo without what it says about where and with what it was taken: GPS, the camera's make, model and
 * serial, capture time, XMP, comments and text chunks all go (CLAUDE.md: location metadata is stripped at upload).
 * The image data is copied byte for byte, never decoded or re-encoded, so the stylist's work looks exactly as
 * uploaded. The EXIF orientation is the one tag kept, so the photo still shows the right way up.
 *
 * <p>Ported from pokemans' {@code intake/ExactCopy} (its {@code of}, which keeps only the orientation). The two
 * copies are separate: a fix in one doesn't reach the other.
 */
final class MetadataStripper {

    /** PNG chunks that describe the pixels; text, time and EXIF chunks are left out. */
    private static final Set<String> PNG_KEPT = Set.of("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT",
            "pHYs", "bKGD");
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private MetadataStripper() {
    }

    /**
     * The photo without its metadata, or null when it isn't a JPEG or PNG this can read. Call only on images
     * {@link com.lookmate.app.storage.ImageGuard} let through.
     */
    static byte[] strip(byte[] image) {
        int orientation = orientation(image);
        byte[] tiff = orientation == 1 ? null : orientationTiff(orientation);
        try {
            if (image.length > 3 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8) {
                return jpeg(image, tiff);
            }
            if (image.length > PNG_SIGNATURE.length && Arrays.equals(image, 0, PNG_SIGNATURE.length, PNG_SIGNATURE, 0, PNG_SIGNATURE.length)) {
                return png(image, tiff);
            }
        } catch (IndexOutOfBoundsException e) {
            // Truncated or malformed: no copy rather than a broken one.
        }
        return null;
    }

    /** The EXIF orientation, 1 (as stored) when there is none. */
    static int orientation(byte[] image) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(image));
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 == null || !ifd0.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return 1;
            }
            int value = ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            return value >= 1 && value <= 8 ? value : 1;
        } catch (ImageProcessingException | IOException | MetadataException | RuntimeException e) {
            return 1;
        }
    }

    /**
     * Keeps the tables, frame and scans, and of the application segments only the JFIF header, the ICC colour profile
     * and Adobe's colour transform: everything a decoder needs to show the same pixels. Stops at the end of the image,
     * so data a phone appends after it (a second image, a depth or gain map) is left out too.
     */
    private static byte[] jpeg(byte[] in, byte[] tiff) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.write(0xFF);
        out.write(0xD8);
        boolean orientationWritten = tiff == null;
        int i = 2;
        while (true) {
            if ((in[i] & 0xFF) != 0xFF) {
                return null;
            }
            while ((in[i] & 0xFF) == 0xFF) {
                i++;
            }
            int marker = in[i++] & 0xFF;
            if (marker == 0xD9) {
                out.write(0xFF);
                out.write(0xD9);
                return out.toByteArray();
            }
            int length = ((in[i] & 0xFF) << 8) | (in[i + 1] & 0xFF);
            int end = i + length;
            if (length < 2 || end > in.length) {
                return null;
            }
            boolean keep = switch (marker) {
                case 0xE0 -> startsWith(in, i + 2, "JFIF\0");
                case 0xE2 -> startsWith(in, i + 2, "ICC_PROFILE\0");
                case 0xEE -> startsWith(in, i + 2, "Adobe");
                // Tables, frames, restart interval, arithmetic conditioning, number of lines, scans.
                case 0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCC, 0xCD, 0xCE, 0xCF, 0xDB, 0xDC, 0xDD, 0xDA -> true;
                default -> false;
            };
            if (!orientationWritten && marker != 0xE0) {
                // After the JFIF header, which must come first, and before anything else.
                out.writeBytes(exifSegment(tiff));
                orientationWritten = true;
            }
            if (keep) {
                out.write(0xFF);
                out.write(marker);
                out.write(in, i, length);
            }
            i = end;
            if (marker == 0xDA) {
                // The entropy-coded scan runs to the next marker that isn't a stuffed byte (FF00) or a restart (FFD0-D7).
                int start = i;
                while (!((in[i] & 0xFF) == 0xFF && in[i + 1] != 0 && ((in[i + 1] & 0xFF) < 0xD0 || (in[i + 1] & 0xFF) > 0xD7)
                        && (in[i + 1] & 0xFF) != 0xFF)) {
                    i++;
                }
                out.write(in, start, i - start);
            }
        }
    }

    private static byte[] png(byte[] in, byte[] tiff) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        out.writeBytes(PNG_SIGNATURE);
        int i = PNG_SIGNATURE.length;
        while (true) {
            int length = ByteBuffer.wrap(in, i, 4).getInt();
            int end = i + 12 + length;
            if (length < 0 || end > in.length) {
                return null;
            }
            String type = new String(in, i + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("IDAT") && tiff != null) {
                // Ancillary chunks about the image go before its data.
                out.writeBytes(pngChunk("eXIf", tiff));
                tiff = null;
            }
            if (PNG_KEPT.contains(type)) {
                out.write(in, i, end - i);
            }
            i = end;
            if (type.equals("IEND")) {
                return out.toByteArray();
            }
        }
    }

    /** An APP1 segment holding the EXIF block. */
    private static byte[] exifSegment(byte[] tiff) {
        int length = 2 + 6 + tiff.length;
        ByteBuffer b = ByteBuffer.allocate(2 + length);
        b.put((byte) 0xFF).put((byte) 0xE1).putShort((short) length).put("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1)).put(tiff);
        return b.array();
    }

    /** A big-endian TIFF block with one IFD holding only the orientation (0x0112, a SHORT): 26 bytes. */
    private static byte[] orientationTiff(int orientation) {
        return ByteBuffer.allocate(26)
                .put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8)
                .putShort((short) 1)
                .putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0)
                .putInt(0)
                .array();
    }

    private static byte[] pngChunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.ISO_8859_1);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(typeBytes).put(data).putInt((int) crc.getValue()).array();
    }

    private static boolean startsWith(byte[] in, int at, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.ISO_8859_1);
        return at + p.length <= in.length && Arrays.equals(in, at, at + p.length, p, 0, p.length);
    }
}
