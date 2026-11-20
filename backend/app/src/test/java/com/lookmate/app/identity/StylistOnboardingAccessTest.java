package com.lookmate.app.identity;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The stylist path through identity: the admin creates a stylist org and an invite, the stylist accepts it and the
 * terms. Another stylist and an anonymous caller can't see the org at all (404, not 403).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class StylistOnboardingAccessTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    private TestApi api;

    @BeforeEach
    void setUp() {
        api = new TestApi(mvc, security);
    }

    @Test
    void aStylistJoinsByInviteAndAcceptsTheTerms() throws Exception {
        String orgId = api.stylistOrg("Curl Studio", "auth0|stylist-a");
        api.get("/api/orgs/" + orgId + "/consent", api.user("auth0|stylist-a", List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(Consents.STYLIST_TERMS_VERSION))
                .andExpect(jsonPath("$.acceptedAt").isNotEmpty());
        api.get("/api/me", api.user("auth0|stylist-a", List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("STYLIST"));
    }

    @Test
    void anotherStylistCannotSeeTheOrg() throws Exception {
        String orgId = api.stylistOrg("Fade Room", "auth0|stylist-b");
        api.stylistOrg("Other Suite", "auth0|stylist-c");
        api.get("/api/orgs/" + orgId + "/consent", api.user("auth0|stylist-c", List.of()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousCallersAreRefusedOutsideThePublicRoutes() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/me"))
                .andExpect(status().isUnauthorized());
    }
}
