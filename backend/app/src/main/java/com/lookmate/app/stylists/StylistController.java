package com.lookmate.app.stylists;

import com.lookmate.app.identity.Directory;
import com.lookmate.app.identity.OrgType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A stylist's own pages (org-scoped: members of the STYLIST org, or the admin; anyone else gets 404 from
 * OrgAccess) and the admin's list of stylists.
 */
@RestController
class StylistController {

    record AdminStylistView(UUID orgId, String orgName, ProfileStatus status, String displayName, String market,
                            BookingPlatform bookingPlatform, int readyPhotos, int taggedPhotos, Instant publishedAt) {
    }

    private final ProfileService profiles;
    private final PhotoService photos;
    private final Directory directory;
    private final StylistProfileRepository profileRows;
    private final PhotoRepository photoRows;

    StylistController(ProfileService profiles, PhotoService photos, Directory directory,
                      StylistProfileRepository profileRows, PhotoRepository photoRows) {
        this.profiles = profiles;
        this.photos = photos;
        this.directory = directory;
        this.profileRows = profileRows;
        this.photoRows = photoRows;
    }

    @GetMapping("/api/orgs/{orgId}/stylist")
    ProfileService.StylistHome home(@PathVariable UUID orgId) {
        return profiles.home(orgId);
    }

    @PutMapping("/api/orgs/{orgId}/stylist/profile")
    ProfileService.ProfileView saveProfile(@PathVariable UUID orgId, @RequestBody ProfileService.ProfileRequest request) {
        return profiles.save(orgId, request);
    }

    @PostMapping("/api/orgs/{orgId}/stylist/publish")
    ProfileService.ProfileView publish(@PathVariable UUID orgId) {
        return profiles.publish(orgId);
    }

    @PostMapping("/api/orgs/{orgId}/stylist/hide")
    ProfileService.ProfileView hide(@PathVariable UUID orgId) {
        return profiles.hide(orgId);
    }

    @GetMapping("/api/orgs/{orgId}/photos")
    List<PhotoService.PhotoView> photos(@PathVariable UUID orgId) {
        return photos.list(orgId);
    }

    @PostMapping("/api/orgs/{orgId}/photos")
    @ResponseStatus(HttpStatus.CREATED)
    PhotoService.UploadSlot requestUpload(@PathVariable UUID orgId, @RequestBody PhotoService.UploadRequest request) {
        return photos.requestUpload(orgId, request);
    }

    @PostMapping("/api/orgs/{orgId}/photos/{photoId}/confirm")
    PhotoService.PhotoView confirm(@PathVariable UUID orgId, @PathVariable UUID photoId) {
        return photos.confirm(orgId, photoId);
    }

    @PutMapping("/api/orgs/{orgId}/photos/{photoId}/tags")
    PhotoService.PhotoView tag(@PathVariable UUID orgId, @PathVariable UUID photoId, @RequestBody PhotoService.TagRequest request) {
        return photos.tag(orgId, photoId, request);
    }

    @PutMapping("/api/orgs/{orgId}/photos/order")
    List<PhotoService.PhotoView> reorder(@PathVariable UUID orgId, @RequestBody PhotoService.OrderRequest request) {
        return photos.reorder(orgId, request);
    }

    @PostMapping("/api/orgs/{orgId}/photos/{photoId}/hide")
    PhotoService.PhotoView hidePhoto(@PathVariable UUID orgId, @PathVariable UUID photoId) {
        return photos.hide(orgId, photoId);
    }

    @PostMapping("/api/orgs/{orgId}/photos/{photoId}/show")
    PhotoService.PhotoView showPhoto(@PathVariable UUID orgId, @PathVariable UUID photoId) {
        return photos.show(orgId, photoId);
    }

    @DeleteMapping("/api/orgs/{orgId}/photos/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePhoto(@PathVariable UUID orgId, @PathVariable UUID photoId) {
        photos.delete(orgId, photoId);
    }

    /** Admin: every stylist org, with or without a profile yet. */
    @GetMapping("/api/admin/stylists")
    @Transactional(readOnly = true)
    List<AdminStylistView> adminList() {
        return directory.orgsOfType(OrgType.STYLIST).stream().map(orgId -> {
            StylistProfile p = profileRows.findById(orgId).orElse(null);
            int ready = (int) photoRows.countByOrgIdAndStatusIn(orgId, List.of(Photo.Status.READY));
            int tagged = (int) photoRows.countTaggedReady(orgId);
            return p == null
                    ? new AdminStylistView(orgId, directory.orgName(orgId), null, null, null, null, ready, tagged, null)
                    : new AdminStylistView(orgId, directory.orgName(orgId), p.getStatus(), p.getDisplayName(), p.getMarket(),
                    p.getBookingPlatform(), ready, tagged, p.getPublishedAt());
        }).toList();
    }
}
