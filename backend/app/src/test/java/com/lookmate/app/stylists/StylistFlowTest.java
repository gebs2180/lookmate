package com.lookmate.app.stylists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lookmate.app.storage.PhotoMetadata;
import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.identity.SecurityProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Stylist onboarding end to end against a real database, with storage in memory ({@link FakeBlobStore}): locations,
 * the profile, uploads with their checks, tags, pairs, order, hide and delete, publishing, the public read service,
 * and that no other stylist can touch any of it.
 */
@SpringBootTest(properties = {"lookmate.storage.max-photos-per-stylist=6", "lookmate.stylists.min-tagged-photos-to-publish=2"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeBlobStore.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class StylistFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private FakeBlobStore store;

    @Autowired
    private Stylists stylists;

    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
    }

    private JwtRequestPostProcessor as(String subject) {
        return api.user(subject, List.of());
    }

    private ResultActions putJson(String url, String json, JwtRequestPostProcessor who) throws Exception {
        return mvc.perform(put(url).with(who).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String location(String name, String market) throws Exception {
        return TestApi.read(api.postJson("/api/admin/locations",
                "{\"brand\":\"SALON_LOFTS\",\"name\":\"" + name + "\",\"market\":\"" + market + "\",\"city\":\"Columbus\",\"state\":\"oh\"}",
                api.admin()).andExpect(status().isCreated()), "$.id");
    }

    private String profileJson(String locationId, String bookingUrl) {
        return "{\"displayName\":\"Curl Studio by Ana\",\"locationId\":" + (locationId == null ? "null" : "\"" + locationId + "\"")
                + ",\"bio\":\"Curly cuts\",\"specialties\":[\"CUT\",\"COLOR\",\"CUT\"],\"bookingUrl\":"
                + (bookingUrl == null ? "null" : "\"" + bookingUrl + "\"") + ",\"instagram\":\"@curl.studio\",\"priceTier\":2}";
    }

    /** Upload slot, the browser's PUT, confirm. Returns the photo id. */
    private String upload(String orgId, String subject, byte[] bytes, String contentType) throws Exception {
        return upload(orgId, as(subject), bytes, contentType);
    }

    private String upload(String orgId, JwtRequestPostProcessor who, byte[] bytes, String contentType) throws Exception {
        ResultActions slot = api.postJson("/api/orgs/" + orgId + "/photos",
                "{\"contentType\":\"" + contentType + "\",\"sizeBytes\":" + bytes.length + "}", who).andExpect(status().isCreated());
        String photoId = TestApi.read(slot, "$.photoId");
        assertThat(TestApi.read(slot, "$.method")).isEqualTo("PUT");
        store.upload(Photo.objectKey(UUID.fromString(orgId), UUID.fromString(photoId)), bytes, contentType);
        mvc.perform(post("/api/orgs/" + orgId + "/photos/" + photoId + "/confirm").with(who))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"));
        return photoId;
    }

    private void tag(String orgId, String subject, String photoId, String service, String hairType) throws Exception {
        putJson("/api/orgs/" + orgId + "/photos/" + photoId + "/tags",
                "{\"service\":\"" + service + "\",\"hairType\":\"" + hairType + "\",\"texture\":\"THICK\",\"length\":\"MEDIUM\",\"before\":false,\"pairPhotoId\":null}",
                as(subject)).andExpect(status().isOk());
    }

    @Test
    void theAdminManagesLocationsAndStylistsSeeOnlyActiveOnes() throws Exception {
        String shortNorth = location("Short North", "dayton-oh");
        String easton = location("Easton", "dayton-oh");
        location("Downtown", "cincinnati-oh");
        api.postJson("/api/admin/locations", "{\"brand\":\"SALON_LOFTS\",\"name\":\"X\",\"market\":\"Columbus OH\"}", api.admin())
                .andExpect(status().isBadRequest());

        putJson("/api/admin/locations/" + easton,
                "{\"brand\":\"SALON_LOFTS\",\"name\":\"Easton\",\"market\":\"dayton-oh\",\"active\":false}", api.admin())
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        String orgId = api.stylistOrg("Locations Suite", "auth0|loc-a");
        api.get("/api/locations?market=dayton-oh", as("auth0|loc-a")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(shortNorth))
                .andExpect(jsonPath("$[0].state").value("OH"));
        api.get("/api/admin/locations?market=dayton-oh", api.admin()).andExpect(jsonPath("$.length()").value(2));
        // Stylists can't add or see the admin's list.
        api.postJson("/api/admin/locations", "{\"brand\":\"B\",\"name\":\"N\",\"market\":\"dayton-oh\"}", as("auth0|loc-a"))
                .andExpect(status().isForbidden());
        // An inactive location can't be picked.
        putJson("/api/orgs/" + orgId + "/stylist/profile", profileJson(easton, null), as("auth0|loc-a"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FIELD"));
    }

    @Test
    void aStylistBuildsAProfileAndPortfolioAndGoesLive() throws Exception {
        String loc = location("Polaris", "columbus-oh");
        String orgId = api.stylistOrg("Ana's Suite", "auth0|ana");
        String home = "/api/orgs/" + orgId + "/stylist";

        api.get(home, as("auth0|ana")).andExpect(status().isOk())
                .andExpect(jsonPath("$.termsAccepted").value(true))
                .andExpect(jsonPath("$.profile").doesNotExist())
                .andExpect(jsonPath("$.missing").value(org.hamcrest.Matchers.contains("PROFILE", "TAGGED_PHOTOS")));

        putJson(home + "/profile", profileJson(loc, "http://booksy.com/x"), as("auth0|ana"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FIELD"));
        putJson(home + "/profile", profileJson(loc, "https://booksy.com/en-us/1_ana"), as("auth0|ana"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingPlatform").value("BOOKSY"))
                .andExpect(jsonPath("$.instagram").value("curl.studio"))
                .andExpect(jsonPath("$.specialties.length()").value(2))
                .andExpect(jsonPath("$.market").value("columbus-oh"))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mvc.perform(post(home + "/publish").with(as("auth0|ana")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_READY_TO_PUBLISH"));

        String curly = upload(orgId, "auth0|ana", TestImages.phoneJpeg(1), "image/jpeg");
        String coily = upload(orgId, "auth0|ana", TestImages.png(2), "image/png");
        upload(orgId, "auth0|ana", TestImages.jpeg(3), "image/jpeg");
        tag(orgId, "auth0|ana", curly, "CUT", "CURLY");
        tag(orgId, "auth0|ana", coily, "CUT", "COILY");

        // The stored original lost its location and device metadata.
        byte[] stored = store.read("photos", Photo.objectKey(UUID.fromString(orgId), UUID.fromString(curly)));
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(TestImages.GPS_SECRET).doesNotContain("PhoneMaker");
        assertThat(PhotoMetadata.orientation(stored)).isEqualTo(6);

        api.get(home, as("auth0|ana")).andExpect(jsonPath("$.missing.length()").value(0))
                .andExpect(jsonPath("$.readyPhotos").value(3)).andExpect(jsonPath("$.taggedPhotos").value(2));
        mvc.perform(post(home + "/publish").with(as("auth0|ana")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LIVE"));

        // The public read service.
        UUID org = UUID.fromString(orgId);
        assertThat(stylists.liveProfile(org)).hasValueSatisfying(p -> {
            assertThat(p.bookingUrl()).isEqualTo("https://booksy.com/en-us/1_ana");
            assertThat(p.location().name()).isEqualTo("Polaris");
        });
        assertThat(stylists.readyPhotos("columbus-oh", ServiceType.CUT, List.of(HairType.CURLY)))
                .extracting(Stylists.PortfolioPhoto::photoId).containsExactly(UUID.fromString(curly));
        assertThat(stylists.readyPhotos("columbus-oh", ServiceType.CUT, List.of())).hasSize(2);
        assertThat(stylists.readyPhotos("columbus-oh", ServiceType.COLOR, List.of())).isEmpty();
        assertThat(stylists.readyPhotos("cincinnati-oh", ServiceType.CUT, List.of())).isEmpty();
        assertThat(stylists.photoUrl(UUID.fromString(curly))).hasValueSatisfying(u -> assertThat(u.toString()).contains("signed=get"));
        assertThat(stylists.liveProfiles("columbus-oh")).extracting(Stylists.LiveProfile::orgId).contains(org);

        // A live profile can't lose its booking link.
        putJson(home + "/profile", profileJson(loc, null), as("auth0|ana"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_READY_TO_PUBLISH"));

        // A hidden photo drops out of matching and has no link; shown again, it's back.
        mvc.perform(post("/api/orgs/" + orgId + "/photos/" + coily + "/hide").with(as("auth0|ana")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HIDDEN"));
        assertThat(stylists.readyPhotos("columbus-oh", ServiceType.CUT, List.of())).hasSize(1);
        assertThat(stylists.photoUrl(UUID.fromString(coily))).isEmpty();
        api.get("/api/orgs/" + orgId + "/photos", as("auth0|ana")).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(post("/api/orgs/" + orgId + "/photos/" + coily + "/show").with(as("auth0|ana")))
                .andExpect(jsonPath("$.status").value("READY"));

        // Hidden profiles are out of matching.
        mvc.perform(post(home + "/hide").with(as("auth0|ana"))).andExpect(jsonPath("$.status").value("HIDDEN"));
        assertThat(stylists.liveProfile(org)).isEmpty();
        assertThat(stylists.readyPhotos("columbus-oh", ServiceType.CUT, List.of())).isEmpty();
        assertThat(stylists.profile(org)).hasValueSatisfying(p -> assertThat(p.status()).isEqualTo(ProfileStatus.HIDDEN));

        api.get("/api/admin/stylists", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.orgId == '" + orgId + "')].taggedPhotos").value(2));
    }

    @Test
    void uploadsAreCheckedAndBadOnesAreRemoved() throws Exception {
        String orgId = api.stylistOrg("Checks Suite", "auth0|checks");
        String photos = "/api/orgs/" + orgId + "/photos";

        api.postJson(photos, "{\"contentType\":\"image/heic\",\"sizeBytes\":100}", as("auth0|checks"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_TYPE"));
        api.postJson(photos, "{\"contentType\":\"image/jpeg\",\"sizeBytes\":999999999}", as("auth0|checks"))
                .andExpect(status().isContentTooLarge());

        // Confirm before uploading: nothing there yet.
        String early = TestApi.read(api.postJson(photos, "{\"contentType\":\"image/jpeg\",\"sizeBytes\":10}", as("auth0|checks")), "$.photoId");
        mvc.perform(post(photos + "/" + early + "/confirm").with(as("auth0|checks")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_UPLOADED"));

        // Not an image: refused, and the upload is deleted.
        byte[] text = "not a photo at all".getBytes(StandardCharsets.US_ASCII);
        String fake = TestApi.read(api.postJson(photos, "{\"contentType\":\"image/jpeg\",\"sizeBytes\":" + text.length + "}", as("auth0|checks")), "$.photoId");
        String fakeKey = Photo.objectKey(UUID.fromString(orgId), UUID.fromString(fake));
        store.upload(fakeKey, text, "image/jpeg");
        mvc.perform(post(photos + "/" + fake + "/confirm").with(as("auth0|checks")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("NOT_AN_IMAGE"));
        assertThat(store.objects).doesNotContainKey(fakeKey);
        mvc.perform(post(photos + "/" + fake + "/confirm").with(as("auth0|checks"))).andExpect(status().isNotFound());

        // A PNG declared as a JPEG is refused too.
        byte[] png = TestImages.png(5);
        String lying = TestApi.read(api.postJson(photos, "{\"contentType\":\"image/jpeg\",\"sizeBytes\":" + png.length + "}", as("auth0|checks")), "$.photoId");
        store.upload(Photo.objectKey(UUID.fromString(orgId), UUID.fromString(lying)), png, "image/jpeg");
        mvc.perform(post(photos + "/" + lying + "/confirm").with(as("auth0|checks")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("NOT_AN_IMAGE"));

        // Confirm is idempotent, and the same photo twice is a duplicate.
        String first = upload(orgId, "auth0|checks", TestImages.jpeg(7), "image/jpeg");
        mvc.perform(post(photos + "/" + first + "/confirm").with(as("auth0|checks")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.width").value(64)).andExpect(jsonPath("$.height").value(48));
        byte[] again = TestImages.jpeg(7);
        String dup = TestApi.read(api.postJson(photos, "{\"contentType\":\"image/jpeg\",\"sizeBytes\":" + again.length + "}", as("auth0|checks")), "$.photoId");
        store.upload(Photo.objectKey(UUID.fromString(orgId), UUID.fromString(dup)), again, "image/jpeg");
        mvc.perform(post(photos + "/" + dup + "/confirm").with(as("auth0|checks")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("DUPLICATE_PHOTO"));

        // Only READY and HIDDEN photos are listed.
        api.get(photos, as("auth0|checks")).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(first));
    }

    @Test
    void thePortfolioIsCappedPerStylist() throws Exception {
        String orgId = api.stylistOrg("Cap Suite", "auth0|cap");
        // The cap is 6 here: one pending slot from the last hour counts, so five READY photos and it leaves room for none.
        for (int i = 0; i < 5; i++) {
            upload(orgId, "auth0|cap", TestImages.jpeg(20 + i), "image/jpeg");
        }
        api.postJson("/api/orgs/" + orgId + "/photos", "{\"contentType\":\"image/jpeg\",\"sizeBytes\":100}", as("auth0|cap"))
                .andExpect(status().isCreated());
        api.postJson("/api/orgs/" + orgId + "/photos", "{\"contentType\":\"image/jpeg\",\"sizeBytes\":100}", as("auth0|cap"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHOTO_LIMIT"));
    }

    @Test
    void beforeAndAfterPairsReorderingAndDeleting() throws Exception {
        String orgId = api.stylistOrg("Pairs Suite", "auth0|pairs");
        String photos = "/api/orgs/" + orgId + "/photos";
        String a = upload(orgId, "auth0|pairs", TestImages.jpeg(30), "image/jpeg");
        String b = upload(orgId, "auth0|pairs", TestImages.jpeg(31), "image/jpeg");
        String c = upload(orgId, "auth0|pairs", TestImages.jpeg(32), "image/jpeg");

        String pairWithB = "{\"service\":\"COLOR\",\"hairType\":\"WAVY\",\"texture\":null,\"length\":null,\"before\":true,\"pairPhotoId\":\"" + b + "\"}";
        putJson(photos + "/" + a + "/tags", pairWithB, as("auth0|pairs"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pairPhotoId").value(b)).andExpect(jsonPath("$.before").value(true));
        api.get(photos, as("auth0|pairs"))
                .andExpect(jsonPath("$[?(@.id == '" + b + "')].pairPhotoId").value(a))
                .andExpect(jsonPath("$[?(@.id == '" + b + "')].before").value(false));

        // Pairing A with C instead leaves B unpaired.
        putJson(photos + "/" + a + "/tags", pairWithB.replace(b, c), as("auth0|pairs")).andExpect(status().isOk());
        api.get(photos, as("auth0|pairs"))
                .andExpect(jsonPath("$[?(@.id == '" + b + "')].pairPhotoId").value(org.hamcrest.Matchers.contains((Object) null)))
                .andExpect(jsonPath("$[?(@.id == '" + c + "')].pairPhotoId").value(a));
        putJson(photos + "/" + a + "/tags", pairWithB.replace(b, a), as("auth0|pairs"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAIR"));

        // Reorder: the full list, or nothing changes.
        putJson(photos + "/order", "{\"photoIds\":[\"" + c + "\",\"" + a + "\"]}", as("auth0|pairs"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ORDER"));
        putJson(photos + "/order", "{\"photoIds\":[\"" + c + "\",\"" + a + "\",\"" + b + "\"]}", as("auth0|pairs"))
                .andExpect(status().isOk());
        api.get(photos, as("auth0|pairs")).andExpect(jsonPath("$[0].id").value(c)).andExpect(jsonPath("$[2].id").value(b));

        // Delete: gone from the list and from storage, and its partner is unpaired.
        String key = Photo.objectKey(UUID.fromString(orgId), UUID.fromString(c));
        assertThat(store.objects).containsKey(key);
        mvc.perform(delete(photos + "/" + c).with(as("auth0|pairs"))).andExpect(status().isNoContent());
        assertThat(store.objects).doesNotContainKey(key);
        api.get(photos, as("auth0|pairs")).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.id == '" + a + "')].pairPhotoId").value(org.hamcrest.Matchers.contains((Object) null)));
        assertThat(stylists.readyPhoto(UUID.fromString(c))).isEmpty();
    }

    @Test
    void anotherStylistGets404OnEverything() throws Exception {
        String loc = location("Dublin", "columbus-oh");
        String orgA = api.stylistOrg("Suite A", "auth0|owner-a");
        String orgB = api.stylistOrg("Suite B", "auth0|owner-b");
        putJson("/api/orgs/" + orgA + "/stylist/profile", profileJson(loc, "https://vagaro.com/a"), as("auth0|owner-a")).andExpect(status().isOk());
        String photo = upload(orgA, "auth0|owner-a", TestImages.jpeg(40), "image/jpeg");
        JwtRequestPostProcessor other = as("auth0|owner-b");
        String a = "/api/orgs/" + orgA;

        api.get(a + "/stylist", other).andExpect(status().isNotFound());
        api.get(a + "/photos", other).andExpect(status().isNotFound());
        putJson(a + "/stylist/profile", profileJson(loc, null), other).andExpect(status().isNotFound());
        mvc.perform(post(a + "/stylist/publish").with(other)).andExpect(status().isNotFound());
        mvc.perform(post(a + "/stylist/hide").with(other)).andExpect(status().isNotFound());
        api.postJson(a + "/photos", "{\"contentType\":\"image/jpeg\",\"sizeBytes\":100}", other).andExpect(status().isNotFound());
        mvc.perform(post(a + "/photos/" + photo + "/confirm").with(other)).andExpect(status().isNotFound());
        putJson(a + "/photos/" + photo + "/tags", "{\"service\":\"CUT\",\"hairType\":\"CURLY\",\"before\":false}", other).andExpect(status().isNotFound());
        mvc.perform(post(a + "/photos/" + photo + "/hide").with(other)).andExpect(status().isNotFound());
        mvc.perform(delete(a + "/photos/" + photo).with(other)).andExpect(status().isNotFound());
        // Nor through their own org's URLs.
        String b = "/api/orgs/" + orgB;
        putJson(b + "/photos/" + photo + "/tags", "{\"service\":\"CUT\",\"hairType\":\"CURLY\",\"before\":false}", other).andExpect(status().isNotFound());
        mvc.perform(delete(b + "/photos/" + photo).with(other)).andExpect(status().isNotFound());
        mvc.perform(post(b + "/photos/" + photo + "/confirm").with(other)).andExpect(status().isNotFound());
        // Nor pair one of theirs with someone else's photo.
        String own = upload(orgB, "auth0|owner-b", TestImages.jpeg(41), "image/jpeg");
        putJson(b + "/photos/" + own + "/tags", "{\"service\":\"CUT\",\"hairType\":\"CURLY\",\"before\":true,\"pairPhotoId\":\"" + photo + "\"}", other)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAIR"));

        // Untouched.
        api.get(a + "/photos", as("auth0|owner-a")).andExpect(jsonPath("$[0].status").value("READY")).andExpect(jsonPath("$[0].service").doesNotExist());
        // The admin may look.
        api.get(a + "/stylist", api.admin()).andExpect(status().isOk());
    }

    @Test
    void theAdminCreatesDemoStylistsAndHidesThemAllAtOnce() throws Exception {
        String loc = location("Demo Loft", "demo-oh");
        String orgId = TestApi.read(api.postJson("/api/admin/orgs", "{\"name\":\"Demo: Maya Brooks\",\"type\":\"STYLIST\"}", api.admin())
                .andExpect(status().isCreated()), "$.id");
        // The admin acts for the made-up stylist: no member, no terms needed.
        putJson("/api/orgs/" + orgId + "/stylist/profile", profileJson(loc, "https://example.com/book/maya"), api.admin()).andExpect(status().isOk());
        api.postJson("/api/admin/stylists/" + orgId + "/demo", "{}", api.admin()).andExpect(status().isOk()).andExpect(jsonPath("$.demo").value(true));
        for (int i = 0; i < 2; i++) {
            String photo = upload(orgId, api.admin(), TestImages.jpeg(60 + i), "image/jpeg");
            putJson("/api/orgs/" + orgId + "/photos/" + photo + "/tags",
                    "{\"service\":\"CUT\",\"hairType\":\"COILY\",\"before\":false}", api.admin()).andExpect(status().isOk());
        }
        mvc.perform(post("/api/orgs/" + orgId + "/stylist/publish").with(api.admin())).andExpect(jsonPath("$.status").value("LIVE"));
        assertThat(stylists.liveProfile(UUID.fromString(orgId))).hasValueSatisfying(p -> assertThat(p.demo()).isTrue());

        // Only the admin may mark or hide demos.
        String real = api.stylistOrg("Real Suite", "auth0|real");
        api.postJson("/api/admin/stylists/" + real + "/demo", "{}", as("auth0|real")).andExpect(status().isForbidden());
        api.postJson("/api/admin/stylists/demo/hide", "{}", as("auth0|real")).andExpect(status().isForbidden());

        api.postJson("/api/admin/stylists/demo/hide", "{}", api.admin()).andExpect(status().isOk()).andExpect(jsonPath("$.hidden").value(1));
        assertThat(stylists.liveProfile(UUID.fromString(orgId))).isEmpty();
        api.get("/api/admin/stylists", api.admin()).andExpect(jsonPath("$[?(@.orgId == '" + orgId + "')].demo").value(org.hamcrest.Matchers.contains(true)));
    }

    @Test
    void practiceLabelsAndFromPricesAreOptionalAndShownOnlyForLiveProfiles() throws Exception {
        String loc = location("Clintonville", "practice-oh");
        String orgId = api.stylistOrg("Rae's Suite", "auth0|rae");
        String home = "/api/orgs/" + orgId + "/stylist";
        String profile = "{\"displayName\":\"Rae\",\"locationId\":\"" + loc + "\",\"specialties\":[\"CUT\",\"COLOR\"],"
                + "\"bookingUrl\":\"https://vagaro.com/rae\",\"priceTier\":2,\"practice\":%s}";

        // A price outside $1 to $10,000 is refused.
        putJson(home + "/profile", profile.formatted("{\"fromPrices\":[{\"service\":\"CUT\",\"fromDollars\":0}]}"), as("auth0|rae"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FIELD"));
        // No practice at all is fine: none of it is needed.
        putJson(home + "/profile", profile.formatted("null"), as("auth0|rae")).andExpect(status().isOk())
                .andExpect(jsonPath("$.practice.labels.length()").value(0))
                .andExpect(jsonPath("$.practice.fromPrices.length()").value(0))
                .andExpect(jsonPath("$.practice.consultation").doesNotExist());

        // Repeats collapse; a price for a service not offered (BRAIDS) is dropped; prices follow the specialties' order.
        putJson(home + "/profile", profile.formatted("""
                {"labels":["LGBTQ_AFFIRMING","KIDS_WELCOME","LGBTQ_AFFIRMING"],"languages":["SPANISH","SOMALI"],
                 "fromPrices":[{"service":"COLOR","fromDollars":120},{"service":"BRAIDS","fromDollars":200},{"service":"CUT","fromDollars":65}],
                 "consultation":"FREE","priceDependsOnHair":true}"""), as("auth0|rae"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.practice.labels").value(org.hamcrest.Matchers.contains("LGBTQ_AFFIRMING", "KIDS_WELCOME")))
                .andExpect(jsonPath("$.practice.languages").value(org.hamcrest.Matchers.contains("SPANISH", "SOMALI")))
                .andExpect(jsonPath("$.practice.fromPrices[*].service").value(org.hamcrest.Matchers.contains("CUT", "COLOR")))
                .andExpect(jsonPath("$.practice.fromPrices[0].fromDollars").value(65))
                .andExpect(jsonPath("$.practice.consultation").value("FREE"))
                .andExpect(jsonPath("$.practice.priceDependsOnHair").value(true));
        api.get(home, as("auth0|rae")).andExpect(jsonPath("$.profile.practice.fromPrices.length()").value(2));
        // An unknown label is a bad request, not a silent drop.
        putJson(home + "/profile", profile.formatted("{\"labels\":[\"VOTES_FOR_X\"]}"), as("auth0|rae"))
                .andExpect(status().isBadRequest());

        // Not LIVE: nothing public.
        mvc.perform(get("/api/public/stylists/" + orgId + "/practice")).andExpect(status().isNotFound());

        for (int i = 0; i < 2; i++) {
            tag(orgId, "auth0|rae", upload(orgId, "auth0|rae", TestImages.jpeg(40 + i), "image/jpeg"), "CUT", "WAVY");
        }
        mvc.perform(post(home + "/publish").with(as("auth0|rae"))).andExpect(status().isOk());

        mvc.perform(get("/api/public/stylists/" + orgId + "/practice")).andExpect(status().isOk())
                .andExpect(jsonPath("$.labels.length()").value(2))
                .andExpect(jsonPath("$.fromPrices[1].service").value("COLOR"))
                .andExpect(jsonPath("$.consultation").value("FREE"));
        mvc.perform(get("/api/public/stylists?market=practice-oh")).andExpect(status().isOk())
                .andExpect(jsonPath("$.stylists[0].practice.languages[0]").value("SPANISH"))
                .andExpect(jsonPath("$.stylists[0].practice.fromPrices[0].fromDollars").value(65))
                .andExpect(jsonPath("$.stylists[0].practice.priceDependsOnHair").value(true));
        // Removing a specialty drops its price.
        putJson(home + "/profile", profile.replace("\"CUT\",\"COLOR\"", "\"CUT\"").formatted("""
                {"fromPrices":[{"service":"COLOR","fromDollars":120},{"service":"CUT","fromDollars":70}]}"""), as("auth0|rae"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.practice.fromPrices.length()").value(1))
                .andExpect(jsonPath("$.practice.labels.length()").value(0));

        mvc.perform(post(home + "/hide").with(as("auth0|rae"))).andExpect(status().isOk());
        mvc.perform(get("/api/public/stylists/" + orgId + "/practice")).andExpect(status().isNotFound());
    }

    @Test
    void firstVisitNotesAndTheNewClientStatusAreShownAndConfirmed() throws Exception {
        String loc = location("Bexley", "first-visit-oh");
        String orgId = api.stylistOrg("Jo's Suite", "auth0|jo");
        String home = "/api/orgs/" + orgId + "/stylist";
        String profile = "{\"displayName\":\"Jo\",\"locationId\":\"" + loc + "\",\"specialties\":[\"CUT\",\"COLOR\"],"
                + "\"bookingUrl\":\"https://vagaro.com/jo\",\"practice\":%s,\"newClients\":%s}";

        // A new profile takes new clients, confirmed when it's made, with nothing to confirm yet.
        putJson(home + "/profile", profile.formatted("null", "null"), as("auth0|jo")).andExpect(status().isOk())
                .andExpect(jsonPath("$.practice.newClients.status").value("TAKING"))
                .andExpect(jsonPath("$.practice.firstVisit.colourConsultationFirst").value(false))
                .andExpect(jsonPath("$.practice.firstVisit.note").doesNotExist())
                .andExpect(jsonPath("$.newClientsConfirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.newClientsConfirmDue").value(false));

        // A wait needs 1 to 26 weeks; a note over 280 characters is refused.
        putJson(home + "/profile", profile.formatted("null", "{\"status\":\"TAKING_WITH_WAIT\"}"), as("auth0|jo"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FIELD"));
        putJson(home + "/profile", profile.formatted("null", "{\"status\":\"TAKING_WITH_WAIT\",\"weeksOut\":27}"), as("auth0|jo"))
                .andExpect(status().isBadRequest());
        putJson(home + "/profile", profile.formatted("{\"firstVisit\":{\"note\":\"" + "x".repeat(281) + "\"}}", "null"), as("auth0|jo"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FIELD"));

        putJson(home + "/profile", profile.formatted("""
                {"firstVisit":{"colourConsultationFirst":true,"depositForNewClients":true,"note":"  Park in the back lot; I'm in loft 12. "}}""",
                "{\"status\":\"TAKING_WITH_WAIT\",\"weeksOut\":6}"), as("auth0|jo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.practice.firstVisit.colourConsultationFirst").value(true))
                .andExpect(jsonPath("$.practice.firstVisit.depositForNewClients").value(true))
                .andExpect(jsonPath("$.practice.firstVisit.note").value("Park in the back lot; I'm in loft 12."))
                .andExpect(jsonPath("$.practice.newClients.status").value("TAKING_WITH_WAIT"))
                .andExpect(jsonPath("$.practice.newClients.weeksOut").value(6));
        // Leaving newClients out keeps it; weeks are dropped with any other status.
        putJson(home + "/profile", profile.formatted("null", "null"), as("auth0|jo"))
                .andExpect(jsonPath("$.practice.newClients.weeksOut").value(6))
                .andExpect(jsonPath("$.practice.firstVisit.depositForNewClients").value(false));
        putJson(home + "/profile", profile.formatted("{\"firstVisit\":{\"depositForNewClients\":true}}",
                "{\"status\":\"NOT_TAKING\",\"weeksOut\":6}"), as("auth0|jo"))
                .andExpect(jsonPath("$.practice.newClients.status").value("NOT_TAKING"))
                .andExpect(jsonPath("$.practice.newClients.weeksOut").doesNotExist());

        for (int i = 0; i < 2; i++) {
            tag(orgId, "auth0|jo", upload(orgId, "auth0|jo", TestImages.jpeg(60 + i), "image/jpeg"), "CUT", "CURLY");
        }
        mvc.perform(post(home + "/publish").with(as("auth0|jo"))).andExpect(status().isOk());

        // Not taking new clients: still in the directory, with the status shown.
        mvc.perform(get("/api/public/stylists?market=first-visit-oh")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.stylists[0].practice.newClients.status").value("NOT_TAKING"))
                .andExpect(jsonPath("$.stylists[0].practice.firstVisit.depositForNewClients").value(true));
        mvc.perform(get("/api/public/stylists?market=first-visit-oh&service=CUT&hairType=CURLY"))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/public/stylists/" + orgId + "/practice"))
                .andExpect(jsonPath("$.newClients.status").value("NOT_TAKING"))
                .andExpect(jsonPath("$.firstVisit.depositForNewClients").value(true))
                .andExpect(jsonPath("$.newClientsConfirmedAt").doesNotExist());
        assertThat(stylists.liveProfile(UUID.fromString(orgId))).isPresent();

        // 31 days on, the dashboard asks; one click confirms.
        jdbc.sql("update stylist_profile set new_clients_confirmed_at = now() - interval '31 days' where org_id = ?")
                .params(UUID.fromString(orgId)).update();
        api.get(home, as("auth0|jo")).andExpect(jsonPath("$.profile.newClientsConfirmDue").value(true));
        mvc.perform(post(home + "/new-clients/confirm").with(as("auth0|jo"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.newClientsConfirmDue").value(false))
                .andExpect(jsonPath("$.practice.newClients.status").value("NOT_TAKING"));
        mvc.perform(post(home + "/new-clients/confirm").with(as("auth0|other"))).andExpect(status().isNotFound());

        // The public service, for the digest's confirm link.
        jdbc.sql("update stylist_profile set new_clients_confirmed_at = now() - interval '40 days' where org_id = ?")
                .params(UUID.fromString(orgId)).update();
        java.time.Instant before = java.time.Instant.now().minusSeconds(5);
        assertThat(stylists.confirmNewClients(UUID.fromString(orgId))).hasValueSatisfying(s -> {
            assertThat(s.newClients().status()).isEqualTo(NewClientStatus.NOT_TAKING);
            assertThat(s.confirmedAt()).isAfter(before);
        });
        assertThat(stylists.newClients(UUID.fromString(orgId)).orElseThrow().confirmedAt()).isAfter(before);
        assertThat(stylists.confirmNewClients(UUID.randomUUID())).isEmpty();
    }

    @Test
    void theGenericFirstVisitNotesArePublicAndMostSpecificFirst() throws Exception {
        mvc.perform(get("/api/public/stylists/first-visit-notes?service=CUT&hairType=CURLY")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key").value(org.hamcrest.Matchers.contains("CUT+CURLY", "CUT", "CURLY", "any.photos")));
        mvc.perform(get("/api/public/stylists/first-visit-notes"))
                .andExpect(jsonPath("$[*].key").value(org.hamcrest.Matchers.contains("any.photos", "any.routine")));
        mvc.perform(get("/api/public/stylists/first-visit-notes?service=NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    void nothingIsWrittenBeforeTheTermsAreAccepted() throws Exception {
        String orgId = TestApi.read(api.postJson("/api/admin/orgs", "{\"name\":\"No Terms\",\"type\":\"STYLIST\"}", api.admin()), "$.id");
        String token = TestApi.read(api.postJson("/api/admin/orgs/" + orgId + "/invites", "{}", api.admin()), "$.token");
        mvc.perform(post("/api/invites/" + token + "/accept").with(as("auth0|no-terms"))).andExpect(status().isOk());

        api.get("/api/orgs/" + orgId + "/stylist", as("auth0|no-terms")).andExpect(status().isOk())
                .andExpect(jsonPath("$.termsAccepted").value(false))
                .andExpect(jsonPath("$.missing[0]").value("TERMS"));
        putJson("/api/orgs/" + orgId + "/stylist/profile", profileJson(null, null), as("auth0|no-terms")).andExpect(status().isForbidden());
        api.postJson("/api/orgs/" + orgId + "/photos", "{\"contentType\":\"image/jpeg\",\"sizeBytes\":100}", as("auth0|no-terms"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/orgs/" + orgId + "/stylist/publish").with(as("auth0|no-terms"))).andExpect(status().isForbidden());
    }
}
