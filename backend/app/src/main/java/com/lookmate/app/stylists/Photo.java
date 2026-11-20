package com.lookmate.app.stylists;

import com.lookmate.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One portfolio photo and its tags (table {@code portfolio_photo}). The original is private in object storage under
 * {@link #objectKey}; nobody gets more than a short-lived signed link to it.
 */
@Entity
@Table(name = "portfolio_photo")
class Photo extends AssignedIdEntity<UUID> {

    enum Status { PENDING_UPLOAD, READY, HIDDEN, DELETED }

    @Id
    private UUID id;
    private UUID orgId;
    private String objectKey;
    private String contentType;
    private long sizeBytes;
    private String sha256;
    private Integer width;
    private Integer height;
    @Enumerated(EnumType.STRING)
    private Status status;
    @Enumerated(EnumType.STRING)
    private ServiceType service;
    @Enumerated(EnumType.STRING)
    private HairType hairType;
    @Enumerated(EnumType.STRING)
    private Texture texture;
    @Enumerated(EnumType.STRING)
    private HairLength length;
    private boolean isBefore;
    private UUID pairPhotoId;
    private int position;
    private UUID uploadedBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    protected Photo() {
    }

    Photo(UUID orgId, String contentType, long sizeBytes, int position, UUID uploadedBy) {
        this.id = UUID.randomUUID();
        this.orgId = orgId;
        this.objectKey = objectKey(orgId, id);
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.status = Status.PENDING_UPLOAD;
        this.position = position;
        this.uploadedBy = uploadedBy;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    static String objectKey(UUID orgId, UUID photoId) {
        return "stylists/" + orgId + "/photos/" + photoId;
    }

    /** The upload passed its checks; {@code storedSize} is after the metadata was taken out. */
    void ready(String sha256, int width, int height, long storedSize) {
        this.sha256 = sha256;
        this.width = width;
        this.height = height;
        this.sizeBytes = storedSize;
        this.status = Status.READY;
        touch();
    }

    void hide() {
        status = Status.HIDDEN;
        touch();
    }

    void show() {
        status = Status.READY;
        touch();
    }

    void delete() {
        status = Status.DELETED;
        pairPhotoId = null;
        deletedAt = Instant.now();
        updatedAt = deletedAt;
    }

    void tag(ServiceType service, HairType hairType, Texture texture, HairLength length) {
        this.service = service;
        this.hairType = hairType;
        this.texture = texture;
        this.length = length;
        touch();
    }

    /** One half of a before/after pair, or unpaired with {@code null}. */
    void pair(UUID pairPhotoId, boolean before) {
        this.pairPhotoId = pairPhotoId;
        this.isBefore = pairPhotoId != null && before;
        touch();
    }

    void moveTo(int position) {
        if (this.position != position) {
            this.position = position;
            touch();
        }
    }

    boolean isVisibleToOwner() {
        return status == Status.READY || status == Status.HIDDEN;
    }

    boolean isTagged() {
        return service != null && hairType != null;
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    Stylists.PortfolioPhoto view() {
        return new Stylists.PortfolioPhoto(id, orgId, service, hairType, texture, length, isBefore, pairPhotoId, position,
                width, height);
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getOrgId() {
        return orgId;
    }

    String getObjectKey() {
        return objectKey;
    }

    String getContentType() {
        return contentType;
    }

    long getSizeBytes() {
        return sizeBytes;
    }

    String getSha256() {
        return sha256;
    }

    Integer getWidth() {
        return width;
    }

    Integer getHeight() {
        return height;
    }

    Status getStatus() {
        return status;
    }

    ServiceType getService() {
        return service;
    }

    HairType getHairType() {
        return hairType;
    }

    Texture getTexture() {
        return texture;
    }

    HairLength getLength() {
        return length;
    }

    boolean isBefore() {
        return isBefore;
    }

    UUID getPairPhotoId() {
        return pairPhotoId;
    }

    int getPosition() {
        return position;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
