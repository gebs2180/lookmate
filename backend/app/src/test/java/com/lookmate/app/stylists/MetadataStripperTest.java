package com.lookmate.app.stylists;

import static org.assertj.core.api.Assertions.assertThat;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.GpsDirectory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** A stylist's photo keeps every pixel and its orientation, and loses where, when and with what it was taken. */
class MetadataStripperTest {

    @Test
    void aPhoneJpegLosesLocationDeviceAndTimeAndKeepsPixelsAndOrientation() throws Exception {
        byte[] plain = TestImages.jpeg(1);
        byte[] upload = TestImages.phoneJpeg(1);
        assertThat(ImageMetadataReader.readMetadata(new ByteArrayInputStream(upload)).getFirstDirectoryOfType(GpsDirectory.class)).isNotNull();

        byte[] stored = MetadataStripper.strip(upload);

        assertThat(ImageMetadataReader.readMetadata(new ByteArrayInputStream(stored)).getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        assertThat(text(stored)).doesNotContain("PhoneMaker").doesNotContain("2026:11:01").doesNotContain("2026:10:30")
                .doesNotContain(TestImages.GPS_SECRET).doesNotContain("taken at home");
        assertThat(MetadataStripper.orientation(stored)).isEqualTo(6);
        assertThat(pixels(stored)).isEqualTo(pixels(upload));
        // The image data is the upload's own bytes: nothing was decoded and encoded again.
        assertThat(tail(stored, plain.length - 20)).isEqualTo(tail(plain, plain.length - 20));
        // Stripping again changes nothing.
        assertThat(MetadataStripper.strip(stored)).isEqualTo(stored);
    }

    @Test
    void aJpegWithNoMetadataIsUnchanged() {
        byte[] plain = TestImages.jpeg(2);
        assertThat(MetadataStripper.strip(plain)).isEqualTo(plain);
    }

    @Test
    void dataAfterTheEndOfTheImageIsLeftOut() {
        byte[] plain = TestImages.jpeg(3);
        byte[] appended = Arrays.copyOf(plain, plain.length + 10);
        System.arraycopy("depth map!".getBytes(StandardCharsets.ISO_8859_1), 0, appended, plain.length, 10);
        assertThat(MetadataStripper.strip(appended)).isEqualTo(plain);
    }

    @Test
    void aPngLosesItsTextChunks() throws Exception {
        byte[] plain = TestImages.png(4);
        String secret = TestImages.GPS_SECRET;
        byte[] upload = ByteBuffer.allocate(plain.length + 12 + 5 + secret.length()).put(plain, 0, 33)
                .put(chunk("tEXt", ("Note\0" + secret).getBytes(StandardCharsets.ISO_8859_1))).put(plain, 33, plain.length - 33).array();

        byte[] stored = MetadataStripper.strip(upload);

        assertThat(text(stored)).doesNotContain(secret);
        assertThat(stored).isEqualTo(plain);
    }

    @Test
    void somethingThatIsntAnImageGetsNoCopy() {
        assertThat(MetadataStripper.strip("not an image".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(MetadataStripper.strip(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x7F})).isNull();
    }

    private static int[] pixels(byte[] image) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(image));
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    private static byte[] tail(byte[] b, int n) {
        return Arrays.copyOfRange(b, b.length - n, b.length);
    }

    private static String text(byte[] b) {
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    private static byte[] chunk(String type, byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(type.getBytes(StandardCharsets.ISO_8859_1));
        crc.update(data);
        return ByteBuffer.allocate(12 + data.length).putInt(data.length).put(type.getBytes(StandardCharsets.ISO_8859_1)).put(data)
                .putInt((int) crc.getValue()).array();
    }
}
