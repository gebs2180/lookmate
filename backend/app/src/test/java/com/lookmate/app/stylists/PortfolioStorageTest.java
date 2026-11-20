package com.lookmate.app.stylists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.identity.SecurityProperties;
import com.lookmate.app.storage.BlobStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The upload path through real object storage (MinIO): the browser's presigned PUT with the signed headers, the
 * confirm rewriting the original without its metadata, a signed link that serves it, and delete removing it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PortfolioStorageTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private BlobStore blobs;

    @Autowired
    private Stylists stylists;

    @Test
    void aPhoneUploadIsStoredWithoutItsLocationAndServedBySignedLink() throws Exception {
        TestApi api = new TestApi(mvc, security);
        String orgId = api.stylistOrg("Storage Suite", "auth0|storage");
        var me = api.user("auth0|storage", List.of());
        byte[] upload = TestImages.phoneJpeg(50);

        String slot = api.postJson("/api/orgs/" + orgId + "/photos",
                "{\"contentType\":\"image/jpeg\",\"sizeBytes\":" + upload.length + "}", me)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String photoId = JsonPath.read(slot, "$.photoId");
        Map<String, Object> headers = JsonPath.read(slot, "$.headers");
        assertThat(api.put(JsonPath.read(slot, "$.url"), headers, upload)).isEqualTo(200);
        // The slot can't be used again to overwrite the photo.
        assertThat(api.put(JsonPath.read(slot, "$.url"), headers, upload)).isEqualTo(412);

        String confirmed = mvc.perform(post("/api/orgs/" + orgId + "/photos/" + photoId + "/confirm").with(me))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
                .andReturn().getResponse().getContentAsString();

        byte[] served = api.download(JsonPath.read(confirmed, "$.url"));
        assertThat(new String(served, StandardCharsets.ISO_8859_1)).doesNotContain(TestImages.GPS_SECRET).doesNotContain("PhoneMaker");
        assertThat(served).isEqualTo(MetadataStripper.strip(upload));
        assertThat(stylists.photoBytes(UUID.fromString(photoId))).hasValueSatisfying(b -> assertThat(b).isEqualTo(served));

        mvc.perform(delete("/api/orgs/" + orgId + "/photos/" + photoId).with(me)).andExpect(status().isNoContent());
        assertThat(blobs.head(TestcontainersConfiguration.BUCKET, Photo.objectKey(UUID.fromString(orgId), UUID.fromString(photoId)))).isEmpty();
    }
}
