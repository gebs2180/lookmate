package com.lookmate.app.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.GpsDirectory;
import com.jayway.jsonpath.JsonPath;
import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.identity.SecurityProperties;
import com.lookmate.app.storage.BlobStore;
import com.lookmate.app.storage.StorageProperties;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The inspiration photo through real object storage (MinIO): the browser's presigned PUT, the confirm that strips its
 * location, the client's short-lived link, and retention deleting it.
 */
@SpringBootTest(properties = "lookmate.retention.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class InspirationStorageTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private BlobStore blobs;

    @Autowired
    private StorageProperties storage;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private InspirationRetention retention;

    @Test
    void thePhotoIsUploadedStrippedShownAndDeletedOnSchedule() throws Exception {
        TestApi api = new TestApi(mvc, security);
        byte[] photo = TestPhotos.jpegWithGps();
        String quiz = IntakeFlowTest.quiz("ola@example.com", "Ola").replace("5000", Integer.toString(photo.length));
        String created = mvc.perform(post("/api/public/match-requests").contentType(MediaType.APPLICATION_JSON).content(quiz))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(created, "$.token");
        String url = JsonPath.read(created, "$.upload.url");
        Map<String, Object> headers = JsonPath.read(created, "$.upload.headers");

        assertThat(api.put(url, headers, photo)).isEqualTo(200);
        assertThat(api.put(url, headers, photo)).as("the slot can't overwrite").isNotEqualTo(200);

        String view = mvc.perform(post("/api/public/match-requests/" + token + "/inspiration/confirm"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("RECEIVED"))
                .andReturn().getResponse().getContentAsString();
        byte[] shown = api.download(JsonPath.read(view, "$.photoUrl"));
        assertThat(ImageMetadataReader.readMetadata(new ByteArrayInputStream(shown)).getFirstDirectoryOfType(GpsDirectory.class)).isNull();

        UUID id = jdbc.sql("select match_request_id from match_request_link where token_hash = :h").param("h", LinkTokens.hash(token))
                .query(UUID.class).single();
        jdbc.sql("update match_request set created_at = now() - interval '40 days' where id = :id").param("id", id).update();
        assertThat(retention.run(Instant.now())).isGreaterThanOrEqualTo(1);
        assertThat(blobs.head(storage.photoBucket(), IntakeService.objectKey(id))).isEmpty();
    }
}
