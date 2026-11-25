package com.lookmate.app.stylists;

import com.lookmate.app.storage.BlobStore;
import com.lookmate.app.storage.StorageProperties;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.CubicCurve2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A LIVE stylist's public page ({@code combpatible.app/s/<slug>}) and the single-stylist view behind the directory's
 * portfolio dialog: the portfolio grouped by hair type, what to expect in their chair, first-visit notes and the
 * new-client status. Only what the stylist publishes: no email, no booking link (Book goes through tracking's
 * redirect). Photos are short-lived signed links, never public URLs.
 *
 * <p>The social preview image is built only from the stylist's own READY portfolio photos, never a client photo.
 */
@Service
class StylistPages {

    /** Hair-type order on the page; untagged photos come last, as "More work". */
    private static final List<HairType> GROUP_ORDER = List.of(HairType.STRAIGHT, HairType.WAVY, HairType.CURLY, HairType.COILY);
    static final int OG_WIDTH = 1200;
    static final int OG_HEIGHT = 630;
    private static final int OG_BAND = 70;
    private static final int OG_PHOTOS = 3;
    private static final int OG_CACHE = 64;
    private static final Color OXBLOOD = new Color(0x8a1c2c);
    private static final Color PAPER = new Color(0xf5f0e8);

    record PagePhoto(UUID photoId, String url, ServiceType service, HairType hairType, boolean before, Integer width, Integer height) {
    }

    /** {@code hairType} null: photos not tagged with a hair type. */
    record PhotoGroup(HairType hairType, List<PagePhoto> photos) {
    }

    /**
     * {@code finderPath}: the "Not sure what to book?" link ({@code /c/<partner>}), only on the finder partner's page.
     * {@code demo}: a made-up stylist; reachable only through the directory's dialog, never by address.
     */
    record StylistPage(UUID orgId, String slug, String displayName, String locationName, String city, String bio,
                       List<ServiceType> specialties, String instagram, boolean demo, Practice practice,
                       List<PhotoGroup> photoGroups, String finderPath) {
    }

    /** What a link preview needs. */
    record Preview(UUID orgId, String slug, String displayName, String locationName, String city, Instant updatedAt,
                   boolean hasPhotos) {
    }

    private final StylistProfileRepository profiles;
    private final PhotoRepository photos;
    private final SalonLocationRepository locations;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final StylistsProperties properties;
    private final Map<String, byte[]> ogCache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > OG_CACHE;
        }
    });

    StylistPages(StylistProfileRepository profiles, PhotoRepository photos, SalonLocationRepository locations, BlobStore blobs,
                 StorageProperties storage, StylistsProperties properties) {
        this.profiles = profiles;
        this.photos = photos;
        this.locations = locations;
        this.blobs = blobs;
        this.storage = storage;
        this.properties = properties;
    }

    /** The page at {@code /s/<slug>}: LIVE and not a demo stylist, or empty. */
    @Transactional(readOnly = true)
    public Optional<StylistPage> bySlug(String slug) {
        return live(slug).map(this::page);
    }

    /** One LIVE stylist, demo ones included (labelled), for the directory's portfolio dialog. */
    @Transactional(readOnly = true)
    public Optional<StylistPage> byOrg(UUID orgId) {
        return profiles.findById(orgId).filter(p -> p.getStatus() == ProfileStatus.LIVE).map(this::page);
    }

    @Transactional(readOnly = true)
    public Optional<Preview> preview(String slug) {
        return live(slug).map(p -> {
            SalonLocation location = location(p);
            return new Preview(p.getOrgId(), p.getSlug(), p.getDisplayName(), location == null ? null : location.view().name(),
                    location == null ? null : location.view().city(), p.getUpdatedAt(), !ogPhotos(p.getOrgId()).isEmpty());
        });
    }

    private Optional<StylistProfile> live(String slug) {
        if (slug == null || slug.length() > 40) {
            return Optional.empty();
        }
        return profiles.findBySlug(slug).filter(p -> p.getStatus() == ProfileStatus.LIVE && !p.isDemo());
    }

    private SalonLocation location(StylistProfile p) {
        return p.getLocationId() == null ? null : locations.findById(p.getLocationId()).orElse(null);
    }

    private StylistPage page(StylistProfile p) {
        SalonLocation location = location(p);
        Map<HairType, List<PagePhoto>> byType = new EnumMap<>(HairType.class);
        List<PagePhoto> untyped = new ArrayList<>();
        for (Photo photo : photos.findByOrgIdAndStatusInOrderByPositionAscCreatedAtAsc(p.getOrgId(), List.of(Photo.Status.READY))) {
            PagePhoto view = new PagePhoto(photo.getId(), blobs.presignDownload(storage.photoBucket(), photo.getObjectKey()).toString(),
                    photo.getService(), photo.getHairType(), photo.isBefore(), photo.getWidth(), photo.getHeight());
            if (photo.getHairType() == null) {
                untyped.add(view);
            } else {
                byType.computeIfAbsent(photo.getHairType(), k -> new ArrayList<>()).add(view);
            }
        }
        List<PhotoGroup> groups = new ArrayList<>();
        for (HairType type : GROUP_ORDER) {
            if (byType.containsKey(type)) {
                groups.add(new PhotoGroup(type, byType.get(type)));
            }
        }
        if (!untyped.isEmpty()) {
            groups.add(new PhotoGroup(null, untyped));
        }
        String finderPath = p.getOrgId().equals(properties.finderOrgId()) ? properties.finderPath() : null;
        return new StylistPage(p.getOrgId(), p.isDemo() ? null : p.getSlug(), p.getDisplayName(),
                location == null ? null : location.view().name(), location == null ? null : location.view().city(), p.getBio(),
                p.getSpecialties(), p.getInstagram(), p.isDemo(), p.getPractice(), groups, finderPath);
    }

    /**
     * Up to three of the stylist's READY photos for the preview image: after-photos before before-photos, a different
     * hair type each where they can, otherwise in the stylist's own order.
     */
    private List<Photo> ogPhotos(UUID orgId) {
        List<Photo> ready = photos.findByOrgIdAndStatusInOrderByPositionAscCreatedAtAsc(orgId, List.of(Photo.Status.READY));
        List<Photo> ordered = new ArrayList<>(ready.stream().filter(ph -> !ph.isBefore()).toList());
        ordered.addAll(ready.stream().filter(Photo::isBefore).toList());
        List<Photo> chosen = new ArrayList<>();
        Set<HairType> types = new HashSet<>();
        for (Photo ph : ordered) {
            if (chosen.size() < OG_PHOTOS && (ph.getHairType() == null || types.add(ph.getHairType()))) {
                chosen.add(ph);
            }
        }
        for (Photo ph : ordered) {
            if (chosen.size() < OG_PHOTOS && !chosen.contains(ph)) {
                chosen.add(ph);
            }
        }
        return chosen;
    }

    /**
     * The 1200x630 JPEG link previews show: the chosen portfolio photos side by side, cropped to fill, over an
     * oxblood band with the Strand drawn in paper. No text, so no fonts on the server. Empty for a stylist with no
     * page or no READY photos. Decodes photos, so the caller holds a {@link com.lookmate.app.storage.PhotoDecodes} turn.
     */
    Optional<byte[]> ogImage(UUID orgId) {
        // No transaction around the storage reads.
        Optional<StylistProfile> profile = profiles.findById(orgId).filter(p -> p.getStatus() == ProfileStatus.LIVE && !p.isDemo());
        if (profile.isEmpty()) {
            return Optional.empty();
        }
        List<Photo> chosen = ogPhotos(orgId);
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        String key = orgId + ":" + chosen.stream().map(ph -> ph.getId().toString()).reduce((a, b) -> a + "," + b).orElse("");
        byte[] cached = ogCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        List<BufferedImage> images = new ArrayList<>();
        int slot = OG_WIDTH / chosen.size();
        for (Photo ph : chosen) {
            BufferedImage img = decode(blobs.read(storage.photoBucket(), ph.getObjectKey()), slot, OG_HEIGHT - OG_BAND);
            if (img != null) {
                images.add(img);
            }
        }
        if (images.isEmpty()) {
            return Optional.empty();
        }
        byte[] jpeg = compose(images);
        ogCache.put(key, jpeg);
        return Optional.of(jpeg);
    }

    static byte[] compose(List<BufferedImage> images) {
        BufferedImage out = new BufferedImage(OG_WIDTH, OG_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(PAPER);
            g.fillRect(0, 0, OG_WIDTH, OG_HEIGHT);
            int photoHeight = OG_HEIGHT - OG_BAND;
            int x = 0;
            for (int i = 0; i < images.size(); i++) {
                int w = i == images.size() - 1 ? OG_WIDTH - x : OG_WIDTH / images.size();
                drawCover(g, images.get(i), x, 0, w, photoHeight);
                x += w;
            }
            // A thin paper line between photos.
            g.setColor(PAPER);
            for (int i = 1; i < images.size(); i++) {
                g.fillRect(i * (OG_WIDTH / images.size()) - 2, 0, 4, photoHeight);
            }
            g.setColor(OXBLOOD);
            g.fillRect(0, photoHeight, OG_WIDTH, OG_BAND);
            // The Strand: one hair, a loose wave across the band.
            g.setColor(PAPER);
            g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int mid = photoHeight + OG_BAND / 2;
            g.draw(new CubicCurve2D.Double(60, mid + 8, 300, mid - 30, 520, mid + 30, 760, mid));
            g.draw(new CubicCurve2D.Double(760, mid, 900, mid - 18, 1020, mid + 18, 1140, mid - 6));
        } finally {
            g.dispose();
        }
        return jpeg(out);
    }

    private static void drawCover(Graphics2D g, BufferedImage img, int x, int y, int w, int h) {
        double scale = Math.max((double) w / img.getWidth(), (double) h / img.getHeight());
        int sw = (int) Math.round(w / scale);
        int sh = (int) Math.round(h / scale);
        int sx = (img.getWidth() - sw) / 2;
        // Faces and hair sit in the top half more often than not.
        int sy = Math.max(0, (img.getHeight() - sh) / 3);
        g.drawImage(img, x, y, x + w, y + h, sx, sy, sx + sw, sy + sh, null);
    }

    /** Decodes a stored photo, subsampled to about twice the slot it fills, so a 48 MP photo never decodes whole. */
    static BufferedImage decode(byte[] bytes, int slotWidth, int slotHeight) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                int step = Math.max(1, Math.min(w / (slotWidth * 2), h / (slotHeight * 2)));
                ImageReadParam param = reader.getDefaultReadParam();
                param.setSourceSubsampling(step, step, 0, 0);
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] jpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
