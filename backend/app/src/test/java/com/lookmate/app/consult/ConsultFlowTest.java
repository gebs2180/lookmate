package com.lookmate.app.consult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lookmate.app.TestApi;
import com.lookmate.app.TestcontainersConfiguration;
import com.lookmate.app.identity.SecurityProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The finder end to end: the seed loads, both channels answer, sessions are kept without the reaction answer. */
@SpringBootTest(properties = {"lookmate.consult.partner-slug=test-partner", "lookmate.retention.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ConsultFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ConsultRetention retention;

    private static Ruleset.TestCase sheetTest(String expected) throws Exception {
        return SeedRulesetTest.seed().tests().stream().filter(t -> t.expected().equals(expected)).findFirst().orElseThrow();
    }

    private ResultActions submit(String path, Map<String, List<String>> answers) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(RulesetJson.write(Map.of("answers", answers))));
    }

    @Test
    void theSeedIsLoadedAndBothChannelsServeIt() throws Exception {
        mvc.perform(get("/api/public/consult/finder")).andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("GENERAL"))
                .andExpect(jsonPath("$.questions[0].id").value("hair_type"))
                .andExpect(jsonPath("$.questions[?(@.id == 'history')].multiple").value(true));
        mvc.perform(get("/api/public/consult/c/test-partner/finder")).andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("PARTNER"));
        mvc.perform(get("/api/public/consult/c/someone-else/finder")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        assertThat(jdbc.sql("select count(*) from consult_ruleset where source = 'SEED'").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void theGeneralChannelLeavesOutPricesAndLinksAndCarriesTheServiceType() throws Exception {
        Ruleset.TestCase t = sheetTest("BALAYAGE_FULL");
        ResultActions general = submit("/api/public/consult/sessions", t.answers()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome.id").value("BALAYAGE_FULL"))
                .andExpect(jsonPath("$.outcome.kind").value("BOOK"))
                .andExpect(jsonPath("$.outcome.serviceType").value("BALAYAGE"))
                .andExpect(jsonPath("$.outcome.firstVisitNotes[0]").value(org.hamcrest.Matchers.startsWith("Plan for a long appointment")))
                .andExpect(jsonPath("$.outcome.firstVisitNotes.length()").value(3))
                .andExpect(jsonPath("$.outcome.fromPrice").doesNotExist())
                .andExpect(jsonPath("$.outcome.bookingUrl").doesNotExist());
        String id = TestApi.read(general, "$.sessionId");
        mvc.perform(post("/api/public/consult/sessions/" + id + "/book").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_BOOKABLE"));
        mvc.perform(post("/api/public/consult/sessions/" + id + "/find-stylist")).andExpect(status().isNoContent());
        assertThat(jdbc.sql("select find_stylist_at is not null from consult_session where id = :id")
                .param("id", UUID.fromString(id)).query(Boolean.class).single()).isTrue();

        submit("/api/public/consult/c/test-partner/sessions", t.answers()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.channel").value("PARTNER"))
                .andExpect(jsonPath("$.outcome.fromPrice").value(220));
    }

    @Test
    void aConsultationCarriesItsReasonAndTheReactionAnswerIsNeverStored() throws Exception {
        Ruleset.TestCase t = SeedRulesetTest.seed().tests().stream()
                .filter(x -> x.answers().containsKey("reaction") && x.expected().equals("CONSULT_SAFETY")).findFirst().orElseThrow();
        ResultActions result = submit("/api/public/consult/sessions", t.answers()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome.kind").value("CONSULTATION"))
                .andExpect(jsonPath("$.outcome.why").isNotEmpty());
        UUID id = UUID.fromString(TestApi.read(result, "$.sessionId"));
        String stored = jdbc.sql("select answers::text from consult_session where id = :id").param("id", id).query(String.class).single();
        assertThat(stored).doesNotContain("reaction");
        assertThat(jdbc.sql("select market from consult_session where id = :id").param("id", id).query(String.class).single())
                .isEqualTo("columbus-oh");
    }

    @Test
    void answersThatDontFitTheQuestionsAreRefused() throws Exception {
        submit("/api/public/consult/sessions", Map.of("hair_type", List.of("Feathers"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ANSWERS"));
        submit("/api/public/consult/sessions", Map.of("colour", List.of("Red"))).andExpect(status().isBadRequest());
        submit("/api/public/consult/sessions", Map.of("hair_type", List.of("Straight", "Curly"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/public/consult/sessions/" + UUID.randomUUID() + "/find-stylist")).andExpect(status().isNotFound());
    }

    @Test
    void skippingEveryQuestionStillReachesAnOutcome() throws Exception {
        submit("/api/public/consult/sessions", Map.of()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.outcome.kind").value("CONSULTATION"));
    }

    @Test
    void anUploadedVersionIsCheckedPublishedAndServedWithTheTickBoxRecorded() throws Exception {
        TestApi api = new TestApi(mvc, security);
        Ruleset seed = SeedRulesetTest.seed();

        // A failing version is refused with its problems, and nothing changes.
        List<Ruleset.TestCase> wrong = new ArrayList<>(seed.tests());
        wrong.set(0, new Ruleset.TestCase(wrong.get(0).name(), wrong.get(0).answers(), "CUT"));
        Ruleset bad = new Ruleset(1, seed.name(), null, seed.questions(), seed.outcomes(), seed.rules(), wrong);
        int before = jdbc.sql("select count(*) from consult_ruleset").query(Integer.class).single();
        api.postJson("/api/admin/consult/rulesets", RulesetJson.write(bad), api.admin())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RULESET_INVALID"))
                .andExpect(jsonPath("$.problems[0]").value(org.hamcrest.Matchers.startsWith("Test 1")));
        assertThat(jdbc.sql("select count(*) from consult_ruleset").query(Integer.class).single()).isEqualTo(before);
        api.postJson("/api/admin/consult/rulesets", RulesetJson.write(seed), api.user("auth0|stylist", List.of()))
                .andExpect(status().isForbidden());

        // The partner's own version: credited, with a booking link.
        List<Ruleset.Outcome> outcomes = seed.outcomes().stream().map(o -> !o.id().equals("BALAYAGE_FULL") ? o
                : new Ruleset.Outcome(o.id(), o.kind(), o.serviceName(), o.serviceType(), o.hours(), o.fromPrice(),
                "https://book.example.com/s/123?ref=x&utm_source=other", o.whatToExpect())).toList();
        Ruleset partner = new Ruleset(1, seed.name(), "Test Stylist, Salon Lofts", seed.questions(), outcomes, seed.rules(), seed.tests());
        ResultActions published = api.postJson("/api/admin/consult/rulesets", RulesetJson.write(partner), api.admin())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("UPLOAD"))
                .andExpect(jsonPath("$.current").value(true));
        int version = Integer.parseInt(TestApi.read(published, "$.version"));
        api.get("/api/admin/consult/rulesets", api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(version));
        api.get("/api/admin/consult/rulesets/" + version, api.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.credit").value("Test Stylist, Salon Lofts"));

        mvc.perform(get("/api/public/consult/c/test-partner/finder"))
                .andExpect(jsonPath("$.credit").value("Test Stylist, Salon Lofts"))
                .andExpect(jsonPath("$.rulesetVersion").value(version));
        ResultActions result = submit("/api/public/consult/c/test-partner/sessions", sheetTest("BALAYAGE_FULL").answers())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rulesetVersion").value(version))
                .andExpect(jsonPath("$.outcome.bookingUrl").value("https://book.example.com/s/123?ref=x&utm_source=combpatible"
                        + "&utm_medium=referral&utm_campaign=columbus-oh&utm_content=consult"));
        UUID id = UUID.fromString(TestApi.read(result, "$.sessionId"));

        mvc.perform(post("/api/public/consult/sessions/" + id + "/book")).andExpect(status().isNoContent());
        assertThat(shared(id)).isFalse();
        mvc.perform(post("/api/public/consult/sessions/" + id + "/book").contentType(MediaType.APPLICATION_JSON)
                .content("{\"shareAnswers\":true}")).andExpect(status().isNoContent());
        mvc.perform(post("/api/public/consult/sessions/" + id + "/book").contentType(MediaType.APPLICATION_JSON)
                .content("{\"shareAnswers\":false}")).andExpect(status().isNoContent());
        assertThat(shared(id)).isTrue();
        assertThat(jdbc.sql("select book_clicks from consult_session where id = :id").param("id", id).query(Integer.class).single())
                .isEqualTo(3);

        // Published versions never change.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("update consult_ruleset set credit = 'x'").update())
                .hasMessageContaining("never changed");
    }

    @Test
    void sessionsGoAfterTheClientContactPeriod() throws Exception {
        String id = TestApi.read(submit("/api/public/consult/sessions", Map.of()).andExpect(status().isCreated()), "$.sessionId");
        retention.run(Instant.now().plus(Duration.ofDays(364)));
        assertThat(exists(id)).isTrue();
        retention.run(Instant.now().plus(Duration.ofDays(366)));
        assertThat(exists(id)).isFalse();
    }

    private boolean shared(UUID id) {
        return jdbc.sql("select share_with_stylist from consult_session where id = :id").param("id", id).query(Boolean.class).single();
    }

    private boolean exists(String id) {
        return jdbc.sql("select count(*) from consult_session where id = :id").param("id", UUID.fromString(id))
                .query(Integer.class).single() == 1;
    }
}
