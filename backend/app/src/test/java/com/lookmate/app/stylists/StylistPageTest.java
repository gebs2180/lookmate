package com.lookmate.app.stylists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.identity.SecurityProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A stylist's public page ({@code /s/<slug>}): the address, the page and its link preview, Book from the page (tracking
 * source PROFILE), and the same photo refused for a second stylist.
 */
@SpringBootTest(properties = {"lookmate.stylists.min-tagged-photos-to-publish=1"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FakeBlobStore.Config.class})
@Testcontainers(disabledWithoutDocker = true)
class StylistPageTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private FakeBlobStore store;

    @Autowired
    private JdbcClient jdbc;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
    }

    private JwtRequestPostProcessor as(String subject) {
        return api.user(subject, List.of());
    }

    private ResultActions profile(String orgId, String subject, String displayName, String slug) throws Exception {
        String loc = TestApi.read(api.postJson("/api/admin/locations",
                "{\"brand\":\"SALON_LOFTS\",\"name\":\"Page Loft " + UUID.randomUUID() + "\",\"market\":\"page-oh\",\"city\":\"Columbus\"}",
                api.admin()).andExpect(status().isCreated()), "$.id");
        String json = "{\"displayName\":\"" + displayName + "\",\"locationId\":\"" + loc + "\",\"bio\":\"Curls\",\"specialties\":[\"CUT\"],"
                + "\"bookingUrl\":\"https://booksy.com/en-us/" + (subject == null ? "demo" : subject.replace("|", "-")) + "?ref=x\",\"instagram\":null,\"priceTier\":null"
                + (slug == null ? "" : ",\"slug\":\"" + slug + "\"") + "}";
        return mvc.perform(put("/api/orgs/" + orgId + "/stylist/profile").with(subject == null ? api.admin() : as(subject))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String upload(String orgId, JwtRequestPostProcessor who, byte[] bytes) throws Exception {
        String photoId = TestApi.read(api.postJson("/api/orgs/" + orgId + "/photos",
                "{\"contentType\":\"image/jpeg\",\"sizeBytes\":" + bytes.length + "}", who).andExpect(status().isCreated()), "$.photoId");
        store.upload(Photo.objectKey(UUID.fromString(orgId), UUID.fromString(photoId)), bytes, "image/jpeg");
        return photoId;
    }

    private ResultActions confirm(String orgId, JwtRequestPostProcessor who, String photoId) throws Exception {
        return mvc.perform(post("/api/orgs/" + orgId + "/photos/" + photoId + "/confirm").with(who));
    }

    private void tagged(String orgId, JwtRequestPostProcessor who, byte[] bytes, String hairType) throws Exception {
        String photo = upload(orgId, who, bytes);
        confirm(orgId, who, photo).andExpect(status().isOk());
        mvc.perform(put("/api/orgs/" + orgId + "/photos/" + photo + "/tags").with(who).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"service\":\"CUT\",\"hairType\":\"" + hairType + "\",\"before\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    void aLiveStylistHasAPageAtTheirAddress() throws Exception {
        String orgId = api.stylistOrg("Page Suite", "auth0|page-ana");
        // The address defaults from the name, accents folded.
        profile(orgId, "auth0|page-ana", "Ána Página Test", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("ana-pagina-test"));
        // Nothing to see until LIVE.
        mvc.perform(get("/api/public/stylists/by-slug/ana-pagina-test")).andExpect(status().isNotFound());

        tagged(orgId, as("auth0|page-ana"), TestImages.jpeg(501), "COILY");
        tagged(orgId, as("auth0|page-ana"), TestImages.jpeg(502), "STRAIGHT");
        String untagged = upload(orgId, as("auth0|page-ana"), TestImages.jpeg(503));
        confirm(orgId, as("auth0|page-ana"), untagged).andExpect(status().isOk());
        mvc.perform(post("/api/orgs/" + orgId + "/stylist/publish").with(as("auth0|page-ana"))).andExpect(status().isOk());

        mvc.perform(get("/api/public/stylists/by-slug/ana-pagina-test")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.orgId").value(orgId))
                .andExpect(jsonPath("$.photoGroups.length()").value(3))
                .andExpect(jsonPath("$.photoGroups[0].hairType").value("STRAIGHT"))
                .andExpect(jsonPath("$.photoGroups[1].hairType").value("COILY"))
                .andExpect(jsonPath("$.photoGroups[2].hairType").doesNotExist())
                .andExpect(jsonPath("$.practice.newClients.status").value("TAKING"))
                .andExpect(jsonPath("$.finderPath").doesNotExist())
                .andExpect(jsonPath("$.bookingUrl").doesNotExist());
        // The same for the directory's portfolio dialog, by id.
        mvc.perform(get("/api/public/stylists/" + orgId)).andExpect(status().isOk()).andExpect(jsonPath("$.slug").value("ana-pagina-test"));

        // The link preview: escaped tags, an image built from the stylist's own photos.
        String html = mvc.perform(get("/api/public/stylists/preview/ana-pagina-test")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<meta property=\"og:title\" content=\"Ána Página Test · Combpatible\">")
                .contains("/s/ana-pagina-test").contains("/api/public/stylists/" + orgId + "/og.jpg?v=")
                .contains("summary_large_image");
        byte[] og = mvc.perform(get("/api/public/stylists/" + orgId + "/og.jpg")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(og));
        assertThat(image.getWidth()).isEqualTo(1200);
        assertThat(image.getHeight()).isEqualTo(630);

        // The stylist renames their page; a taken or malformed address is refused.
        profile(orgId, "auth0|page-ana", "Ána Página Test", "combpatible.app/s/Ana-Curls").andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("ana-curls"));
        mvc.perform(get("/api/public/stylists/by-slug/ana-pagina-test")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/stylists/by-slug/ana-curls")).andExpect(status().isOk());
        profile(orgId, "auth0|page-ana", "Ána Página Test", "-no-").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD"));

        String other = api.stylistOrg("Page Suite Two", "auth0|page-bo");
        profile(other, "auth0|page-bo", "Ána Página Test", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("ana-pagina-test"));
        String third = api.stylistOrg("Page Suite Three", "auth0|page-cy");
        profile(third, "auth0|page-cy", "Ana Curls", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("ana-curls-2"));
        profile(third, "auth0|page-cy", "Ana Curls", "ana-curls").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLUG_TAKEN"));

        // Hidden: no page.
        mvc.perform(post("/api/orgs/" + orgId + "/stylist/hide").with(as("auth0|page-ana"))).andExpect(status().isOk());
        mvc.perform(get("/api/public/stylists/by-slug/ana-curls")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/stylists/preview/ana-curls")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/stylists/" + orgId + "/og.jpg")).andExpect(status().isNotFound());
    }

    @Test
    void bookFromTheStylistsPageIsTrackedAsProfile() throws Exception {
        String orgId = api.stylistOrg("Book Page Suite", "auth0|page-dee");
        profile(orgId, "auth0|page-dee", "Dee Book Page", null).andExpect(status().isOk());
        tagged(orgId, as("auth0|page-dee"), TestImages.jpeg(511), "WAVY");
        String book = "/api/public/stylists/" + orgId + "/book";
        // Not LIVE: no redirect, nothing recorded.
        mvc.perform(get(book)).andExpect(status().isNotFound());
        mvc.perform(post("/api/orgs/" + orgId + "/stylist/publish").with(as("auth0|page-dee"))).andExpect(status().isOk());

        String location = mvc.perform(get(book).param("via", "qr")).andExpect(status().isFound())
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn().getResponse().getHeader("Location");
        assertThat(location).startsWith("https://booksy.com/en-us/auth0-page-dee?ref=x&")
                .contains("utm_source=combpatible").contains("utm_content=profile").contains("utm_campaign=page-oh");
        mvc.perform(get(book).param("via", "<script>")).andExpect(status().isFound());
        assertThat(jdbc.sql("select via from profile_click where org_id = :org order by occurred_at")
                .param("org", UUID.fromString(orgId)).query(String.class).list()).containsExactly("QR", "LINK");
        // Never a match event: these clients came through the stylist's own link.
        assertThat(jdbc.sql("select count(*) from match_event where org_id = :org").param("org", UUID.fromString(orgId))
                .query(Long.class).single()).isZero();
    }

    @Test
    void demoStylistsHaveNoPageButShowInTheDialog() throws Exception {
        String orgId = TestApi.read(api.postJson("/api/admin/orgs", "{\"name\":\"Demo: Page Sample\",\"type\":\"STYLIST\"}", api.admin())
                .andExpect(status().isCreated()), "$.id");
        profile(orgId, null, "Page Sample Demo", null).andExpect(status().isOk());
        api.postJson("/api/admin/stylists/" + orgId + "/demo", "{}", api.admin()).andExpect(status().isOk());
        tagged(orgId, api.admin(), TestImages.jpeg(521), "CURLY");
        mvc.perform(post("/api/orgs/" + orgId + "/stylist/publish").with(api.admin())).andExpect(status().isOk());

        mvc.perform(get("/api/public/stylists/by-slug/page-sample-demo")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/stylists/preview/page-sample-demo")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/stylists/" + orgId + "/book")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/stylists/" + orgId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.demo").value(true)).andExpect(jsonPath("$.slug").doesNotExist());
    }

    @Test
    void aPhotoAnotherStylistHasIsRefused() throws Exception {
        String first = api.stylistOrg("Dup Suite One", "auth0|dup-one");
        String second = api.stylistOrg("Dup Suite Two", "auth0|dup-two");
        byte[] photo = TestImages.jpeg(531);
        confirm(first, as("auth0|dup-one"), upload(first, as("auth0|dup-one"), photo)).andExpect(status().isOk());

        confirm(second, as("auth0|dup-two"), upload(second, as("auth0|dup-two"), photo))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("PHOTO_ELSEWHERE"));
        // The same stylist again: the friendlier "already in your portfolio".
        confirm(first, as("auth0|dup-one"), upload(first, as("auth0|dup-one"), photo))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("DUPLICATE_PHOTO"));
        // A different photo is fine.
        confirm(second, as("auth0|dup-two"), upload(second, as("auth0|dup-two"), TestImages.jpeg(532))).andExpect(status().isOk());
    }
}
