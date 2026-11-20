package com.lookmate.app.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.GpsDirectory;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Client photos lose their location and device metadata, and keep their pixels and orientation. */
class PhotoMetadataTest {

    @Test
    void aJpegLosesItsGpsAndMakeAndKeepsItsOrientationAndPixels() throws Exception {
        byte[] original = TestPhotos.jpegWithGps();
        Metadata before = ImageMetadataReader.readMetadata(new ByteArrayInputStream(original));
        assertThat(before.getFirstDirectoryOfType(GpsDirectory.class)).as("the test photo has a GPS block").isNotNull();

        byte[] clean = PhotoMetadata.withoutMetadata(original);

        assertThat(clean).isNotNull();
        Metadata after = ImageMetadataReader.readMetadata(new ByteArrayInputStream(clean));
        assertThat(after.getFirstDirectoryOfType(GpsDirectory.class)).isNull();
        ExifIFD0Directory ifd0 = after.getFirstDirectoryOfType(ExifIFD0Directory.class);
        assertThat(ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION)).isEqualTo(6);
        assertThat(ifd0.containsTag(ExifIFD0Directory.TAG_MAKE)).isFalse();
        assertThat(new String(clean, StandardCharsets.ISO_8859_1)).doesNotContain(TestPhotos.MAKE);
        assertSamePixels(original, clean);
    }

    @Test
    void aPngLosesItsTextChunks() throws Exception {
        byte[] original = TestPhotos.pngWithText();
        byte[] clean = PhotoMetadata.withoutMetadata(original);
        assertThat(clean).isNotNull();
        assertThat(new String(original, StandardCharsets.ISO_8859_1)).contains("Elm St");
        assertThat(new String(clean, StandardCharsets.ISO_8859_1)).doesNotContain("Elm St");
        assertSamePixels(original, clean);
    }

    @Test
    void aPhotoWithoutMetadataIsCopiedAsItIs() throws Exception {
        byte[] png = TestPhotos.encode("png");
        assertThat(PhotoMetadata.withoutMetadata(png)).isEqualTo(png);
    }

    @Test
    void anythingElseHasNoCopy() {
        assertThat(PhotoMetadata.withoutMetadata("not an image at all".getBytes(StandardCharsets.UTF_8))).isNull();
        byte[] truncated = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x00};
        assertThat(PhotoMetadata.withoutMetadata(truncated)).isNull();
    }

    private static void assertSamePixels(byte[] a, byte[] b) throws Exception {
        BufferedImage x = ImageIO.read(new ByteArrayInputStream(a));
        BufferedImage y = ImageIO.read(new ByteArrayInputStream(b));
        assertThat(y.getWidth()).isEqualTo(x.getWidth());
        assertThat(y.getHeight()).isEqualTo(x.getHeight());
        for (int i = 0; i < x.getWidth(); i++) {
            for (int j = 0; j < x.getHeight(); j++) {
                assertThat(y.getRGB(i, j)).isEqualTo(x.getRGB(i, j));
            }
        }
    }
}
