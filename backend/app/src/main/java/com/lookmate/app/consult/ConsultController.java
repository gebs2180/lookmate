package com.lookmate.app.consult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Not sure what to book?" for clients, without an account (CLAUDE.md). The general finder sits under
 * {@code /api/public/consult}, the partner's own link under {@code /api/public/consult/c/{slug}}. Rate-limited per IP
 * ({@link ConsultRateLimit}).
 */
@RestController
class ConsultController {

    static final String BASE = "/api/public/consult";

    /**
     * The client's picks, by question id; skipped questions are left out. Answers are the answer texts exactly as
     * {@link ConsultService.Question#answers()} gives them.
     */
    record Answers(@NotNull @Size(max = 40) Map<String, @NotNull @Size(max = 40) List<@NotBlank String>> answers) {
    }

    /** {@code shareAnswers}: the client ticked "send my answers to the stylist" (unticked by default; missing means unticked). */
    record BookClick(Boolean shareAnswers) {
    }

    private final ConsultService consult;

    ConsultController(ConsultService consult) {
        this.consult = consult;
    }

    @GetMapping(BASE + "/finder")
    ConsultService.Finder finder() {
        return consult.finder(ConsultSessions.Channel.GENERAL);
    }

    @GetMapping(BASE + "/c/{slug}/finder")
    ConsultService.Finder partnerFinder(@PathVariable String slug) {
        return consult.finder(consult.partner(slug));
    }

    @PostMapping(BASE + "/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    ConsultService.Result submit(@Valid @RequestBody Answers body) {
        return consult.submit(ConsultSessions.Channel.GENERAL, body.answers());
    }

    @PostMapping(BASE + "/c/{slug}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    ConsultService.Result partnerSubmit(@PathVariable String slug, @Valid @RequestBody Answers body) {
        return consult.submit(consult.partner(slug), body.answers());
    }

    /** A Book click on a partner result. 409 NOT_BOOKABLE when the result has no booking link (or is general). */
    @PostMapping(BASE + "/sessions/{id}/book")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void book(@PathVariable UUID id, @RequestBody(required = false) BookClick body) {
        consult.book(id, body != null && Boolean.TRUE.equals(body.shareAnswers()));
    }

    /** The client went on to find-my-stylist with the outcome's service type. */
    @PostMapping(BASE + "/sessions/{id}/find-stylist")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void findStylist(@PathVariable UUID id) {
        consult.findStylist(id);
    }
}
