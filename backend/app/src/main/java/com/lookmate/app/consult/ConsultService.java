package com.lookmate.app.consult;

import com.lookmate.app.api.ApiException;
import com.lookmate.app.consult.ConsultSessions.Channel;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The service finder for clients (docs/modules/consult.md). Two channels share the pilot's ruleset: the partner's own
 * link ({@code /c/<slug>}), which shows their prices and booking links, and the general button, which leaves those out
 * and hands off to find-my-stylist with the outcome's service type. No client identity is kept: a session is its
 * random id, the stored answers and the outcome.
 */
@Service
class ConsultService {

    enum Code { NOT_FOUND, INVALID_ANSWERS, NO_RULESET, NOT_BOOKABLE }

    /** A question as clients see it. {@code help} is the stylist's help text (the example sheet's are photo ideas). */
    record Question(String id, String text, boolean multiple, String help, List<String> answers) {
    }

    /**
     * The finder's questions.
     *
     * @param credit who wrote the rules, or null (our example ruleset credits nobody)
     */
    record Finder(Channel channel, String name, String credit, int rulesetVersion, List<Question> questions) {
    }

    /**
     * Where the answers lead.
     *
     * @param serviceType  the Combpatible service code, for the hand-off to find-my-stylist
     * @param hours        the usual time in hours, or null
     * @param fromPrice    the partner's from-price in dollars; null on the general channel or when not given
     * @param bookingUrl   the partner's booking link for this service, tagged utm_source=combpatible; null on the
     *                     general channel and when the partner hasn't given one (then there's no Book button)
     * @param whatToExpect what happens in the chair, or null
     * @param why          why the answers lead here; for a consultation, the reason one is needed
     */
    record Outcome(String id, Ruleset.Kind kind, String serviceName, String serviceType, BigDecimal hours,
                   BigDecimal fromPrice, String bookingUrl, String whatToExpect, String why) {
    }

    /** The result of a run. {@code sessionId} is the handle for the Book and find-a-stylist clicks. */
    record Result(UUID sessionId, Channel channel, int rulesetVersion, String credit, Outcome outcome) {
    }

    static final class NotFound extends ApiException {
        NotFound() {
            super(HttpStatus.NOT_FOUND, Code.NOT_FOUND, "We couldn't find that.");
        }
    }

    private final ConsultRulesets rulesets;
    private final ConsultSessions sessions;
    private final ConsultProperties properties;
    private final String market;
    private final Clock clock;

    ConsultService(ConsultRulesets rulesets, ConsultSessions sessions, ConsultProperties properties,
                   @Value("${lookmate.default-market}") String market, Optional<Clock> clock) {
        this.rulesets = rulesets;
        this.sessions = sessions;
        this.properties = properties;
        this.market = market;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** The partner's channel for their slug; any other slug is 404, so links can't be guessed apart from it. */
    Channel partner(String slug) {
        if (slug == null || !slug.equalsIgnoreCase(properties.partnerSlug())) {
            throw new NotFound();
        }
        return Channel.PARTNER;
    }

    @Transactional(readOnly = true)
    Finder finder(Channel channel) {
        ConsultRulesets.Version v = current();
        Ruleset r = rulesets.ruleset(v.id());
        List<Question> questions = r.questions().stream()
                .map(q -> new Question(q.id(), q.text(), q.multiple(), q.help(), q.answers())).toList();
        return new Finder(channel, r.name(), r.credit(), v.version(), questions);
    }

    /** Runs the answers through the current ruleset and keeps the session (stored questions only). */
    @Transactional
    Result submit(Channel channel, Map<String, List<String>> answers) {
        ConsultRulesets.Version v = current();
        Ruleset r = rulesets.ruleset(v.id());
        Optional<String> problem = RulesetValidator.answerProblem(r, answers);
        if (problem.isPresent()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, Code.INVALID_ANSWERS, problem.get());
        }
        var picked = Ruleset.answers(answers);
        Ruleset.Result result = r.evaluate(picked)
                .orElseThrow(() -> new IllegalStateException("Ruleset version " + v.version() + " reached no outcome"));
        UUID id = sessions.insert(v.id(), v.version(), market, channel, r.storable(picked), result.outcome().id(),
                result.ruleIndex(), clock.instant());
        return new Result(id, channel, v.version(), r.credit(), view(channel, result));
    }

    /**
     * A Book click on the partner's link. {@code shareAnswers} is the client's tick on "send my answers to the
     * stylist", unticked by default; it is stored, and only ever turns on.
     */
    @Transactional
    void book(UUID sessionId, boolean shareAnswers) {
        ConsultSessions.Session s = sessions.find(sessionId).orElseThrow(NotFound::new);
        Ruleset.Outcome outcome = rulesets.ruleset(s.rulesetId()).outcome(s.outcomeId()).orElseThrow();
        if (s.channel() != Channel.PARTNER || outcome.bookingUrl() == null) {
            throw new ApiException(HttpStatus.CONFLICT, Code.NOT_BOOKABLE, "This result has no booking link.");
        }
        sessions.booked(sessionId, shareAnswers, clock.instant());
        // TODO(service finder, pilot step 2): when shareAnswers is on, email the stylist the stored answers and the
        //  outcome (notify module). Until then the opt-in is only recorded (docs/modules/consult.md).
    }

    /** The client went on to find-my-stylist from this result. */
    @Transactional
    void findStylist(UUID sessionId) {
        sessions.find(sessionId).orElseThrow(NotFound::new);
        sessions.findStylist(sessionId, clock.instant());
    }

    private ConsultRulesets.Version current() {
        return rulesets.latest(ConsultRulesets.DEFAULT_KEY).orElseThrow(() ->
                new ApiException(HttpStatus.SERVICE_UNAVAILABLE, Code.NO_RULESET, "The service finder isn't ready yet."));
    }

    private Outcome view(Channel channel, Ruleset.Result result) {
        Ruleset.Outcome o = result.outcome();
        boolean partner = channel == Channel.PARTNER;
        return new Outcome(o.id(), o.kind(), o.serviceName(), o.serviceType(), o.hours(),
                partner ? o.fromPrice() : null, partner ? tagged(o.bookingUrl(), market) : null,
                o.whatToExpect(), result.rule().why());
    }

    /**
     * The booking link with Combpatible's UTM tags (CLAUDE.md: utm_source=combpatible), replacing any the link had.
     * Null when there's no link, or it isn't a plain https link (the validator already refuses those).
     */
    static String tagged(String url, String market) {
        if (url == null) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawAuthority() == null || uri.getRawUserInfo() != null) {
            return null;
        }
        StringBuilder query = new StringBuilder();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                if (!pair.isEmpty() && !pair.toLowerCase().matches("utm_(source|medium|campaign|content)(=.*)?")) {
                    query.append(pair).append('&');
                }
            }
        }
        query.append("utm_source=combpatible&utm_medium=referral&utm_campaign=")
                .append(URLEncoder.encode(market, StandardCharsets.UTF_8)).append("&utm_content=consult");
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return "https://" + uri.getRawAuthority() + path + "?" + query
                + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
    }
}
