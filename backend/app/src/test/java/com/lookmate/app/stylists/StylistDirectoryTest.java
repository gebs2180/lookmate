package com.lookmate.app.stylists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lookmate.app.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The public stylist directory: LIVE stylists only, filtered by service and hair type, a few READY photos as signed
 * links, nothing a client shouldn't see. Each test has its own market and its own client address (the directory is
 * rate-limited per IP, and this context is shared).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class StylistDirectoryTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;

    private String market;
    private String ip;
    private UUID uploader;
    private UUID location;
    private int position;

    @BeforeEach
    void setUp() {
        market = "test-" + UUID.randomUUID().toString().substring(0, 8);
        ip = "10.%d.%d.%d".formatted(ThreadLocalRandom.current().nextInt(256), ThreadLocalRandom.current().nextInt(256),
                ThreadLocalRandom.current().nextInt(1, 255));
        uploader = UUID.randomUUID();
        jdbc.sql("insert into app_user (id, subject, created_at) values (?, ?, now())").params(uploader, "seed|" + uploader).update();
        location = UUID.randomUUID();
        jdbc.sql("""
                        insert into salon_location (id, brand, name, market, address, city, state, created_at)
                        values (?, 'SALON_LOFTS', 'Short North', ?, '1 High St', 'Columbus', 'OH', now())
                        """)
                .params(location, market).update();
    }

    @Test
    void listsLiveStylistsOnlyWithSignedPhotosAndNothingPrivate() throws Exception {
        UUID ana = stylist("Ana", "LIVE", false, "{CUT}");
        for (int i = 0; i < 4; i++) {
            photo(ana, "CUT", "CURLY", "READY");
        }
        String hiddenKey = photo(ana, "CUT", "CURLY", "HIDDEN");
        UUID bea = stylist("Bea", "LIVE", true, "{}");
        photo(bea, "COLOR", "WAVY", "READY");
        UUID fay = stylist("Fay", "LIVE", false, "{BRAIDS}");
        stylist("Cy Draft", "DRAFT", false, "{CUT}");
        stylist("Dee Hidden", "HIDDEN", false, "{CUT}");

        String body = browse("").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.market").value(market))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.page").value(0))
                // Real stylists first, then sample ones, each by name.
                .andExpect(jsonPath("$.stylists[*].displayName").value(Matchers.contains("Ana", "Fay", "Bea")))
                .andExpect(jsonPath("$.stylists[0].orgId").value(ana.toString()))
                .andExpect(jsonPath("$.stylists[0].locationName").value("Short North"))
                .andExpect(jsonPath("$.stylists[0].city").value("Columbus"))
                .andExpect(jsonPath("$.stylists[0].specialties[0]").value("CUT"))
                .andExpect(jsonPath("$.stylists[0].instagram").value("handle"))
                .andExpect(jsonPath("$.stylists[0].demo").value(false))
                .andExpect(jsonPath("$.stylists[2].demo").value(true))
                .andExpect(jsonPath("$.stylists[0].photoUrls.length()").value(3))
                .andExpect(jsonPath("$.stylists[0].photoUrls[0]").value(Matchers.containsString("X-Amz-Signature=")))
                .andExpect(jsonPath("$.stylists[0].photoUrls[0]").value(Matchers.containsString("X-Amz-Expires=")))
                .andExpect(jsonPath("$.stylists[1].photoUrls.length()").value(0))
                .andExpect(jsonPath("$..bookingUrl").isEmpty())
                .andExpect(jsonPath("$..bookingPlatform").isEmpty())
                .andExpect(jsonPath("$..email").isEmpty())
                .andExpect(jsonPath("$..photoId").isEmpty())
                .andExpect(jsonPath("$..status").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("booksy.com", hiddenKey, location.toString(), uploader.toString());
    }

    @Test
    void filtersByServiceAndHairType() throws Exception {
        UUID ana = stylist("Ana", "LIVE", false, "{}");
        photo(ana, "COLOR", "CURLY", "READY");
        photo(ana, "CUT", "CURLY", "READY");
        UUID bea = stylist("Bea", "LIVE", false, "{}");
        photo(bea, "CUT", "WAVY", "READY");
        photo(bea, "CUT", "COILY", "HIDDEN"); // hidden photos don't count
        stylist("Fay", "LIVE", false, "{BRAIDS}");

        browse("&service=CUT").andExpect(jsonPath("$.stylists[*].displayName").value(Matchers.contains("Ana", "Bea")))
                .andExpect(jsonPath("$.service").value("CUT"));
        browse("&hairType=CURLY").andExpect(jsonPath("$.stylists[*].displayName").value(Matchers.contains("Ana")));
        browse("&service=CUT&hairType=WAVY").andExpect(jsonPath("$.stylists[*].displayName").value(Matchers.contains("Bea")));
        browse("&service=CUT&hairType=COILY").andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.stylists").isEmpty());
        // A service among a stylist's specialties counts without a photo, but a hair type needs a photo.
        browse("&service=BRAIDS").andExpect(jsonPath("$.stylists[*].displayName").value(Matchers.contains("Fay")));
        browse("&service=BRAIDS&hairType=COILY").andExpect(jsonPath("$.total").value(0));
        // Photos that match the filters come first.
        String colorFirst = browse("&service=COLOR&hairType=CURLY").andReturn().getResponse().getContentAsString();
        assertThat(colorFirst).contains("X-Amz-Signature");
        browse("&service=NOT_A_SERVICE").andExpect(status().isBadRequest());
        browse("&hairType=bald").andExpect(status().isBadRequest());
        browse("&page=-1").andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/stylists?market=" + "Not A Market").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        })).andExpect(status().isBadRequest());
    }

    @Test
    void pagesTwelveAtATime() throws Exception {
        for (int i = 0; i < 13; i++) {
            stylist("Stylist %02d".formatted(i), "LIVE", false, "{}");
        }
        browse("").andExpect(jsonPath("$.total").value(13)).andExpect(jsonPath("$.pageSize").value(12))
                .andExpect(jsonPath("$.stylists.length()").value(12))
                .andExpect(jsonPath("$.stylists[0].displayName").value("Stylist 00"));
        browse("&page=1").andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.stylists.length()").value(1))
                .andExpect(jsonPath("$.stylists[0].displayName").value("Stylist 12"));
        browse("&page=2").andExpect(jsonPath("$.stylists").isEmpty()).andExpect(jsonPath("$.total").value(13));
    }

    private ResultActions browse(String query) throws Exception {
        return mvc.perform(get("/api/public/stylists?market=" + market + query).with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }));
    }

    private UUID stylist(String name, String status, boolean demo, String specialties) {
        UUID org = UUID.randomUUID();
        jdbc.sql("insert into org (id, name, type, created_at) values (?, ?, 'STYLIST', now())").params(org, name).update();
        jdbc.sql("""
                        insert into stylist_profile (org_id, display_name, location_id, market, instagram, booking_url, specialties, demo,
                          status, created_at, updated_at)
                        values (?, ?, ?, ?, 'handle', 'https://booksy.com/secret', cast(? as text[]), ?, ?, now(), now())
                        """)
                .params(org, name, location, market, specialties, demo, status).update();
        return org;
    }

    private String photo(UUID org, String service, String hairType, String status) {
        UUID id = UUID.randomUUID();
        String key = "portfolio/" + org + "/" + id + ".jpg";
        jdbc.sql("""
                        insert into portfolio_photo (id, org_id, object_key, content_type, size_bytes, status, service, hair_type, position,
                          uploaded_by, created_at, updated_at)
                        values (?, ?, ?, 'image/jpeg', 1000, ?, ?, ?, ?, ?, ?, now())
                        """)
                .params(id, org, key, status, service, hairType, ++position, uploader, Timestamp.from(Instant.now()))
                .update();
        return key;
    }
}
