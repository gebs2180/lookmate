package com.lookmate.app.intake;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anonymises a client {@code lookmate.retention.client-contact-days} (365) after the latest request made with their
 * email address (docs/data-retention.md). Email, phone, first name, neighborhood and the free-text note go; the
 * quiz codes (hair type, texture, length, budget, past-issue codes) and the market stay, unlinkable to a person, so
 * matching quality by hair type can still be measured. One request a year keeps a regular client's details.
 *
 * <p>A request whose client gave no email (it's optional, CLAUDE.md 2026-11-20) has no address to link it to others:
 * its first name, neighborhood and note are cleared the same number of days after that request was made.
 */
@Component
public class ClientContactRetention {

    private static final Logger log = LoggerFactory.getLogger(ClientContactRetention.class);

    private final JdbcClient jdbc;
    private final Duration keep;

    ClientContactRetention(JdbcClient jdbc, @Value("${lookmate.retention.client-contact-days:365}") int days) {
        this.jdbc = jdbc;
        this.keep = Duration.of(days, ChronoUnit.DAYS);
    }

    /** Returns how many client rows were anonymised. */
    @Transactional
    public int run(Instant now) {
        Timestamp cutoff = Timestamp.from(now.minus(keep));
        int anonymised = jdbc.sql("""
                        update client c
                        set email = null, phone = null, first_name = null, neighborhood = null, notes = null, anonymised_at = :now
                        where c.anonymised_at is null
                          and c.created_at < :cutoff
                          and not exists (
                            select 1 from client o join match_request r on r.client_id = o.id
                            where c.email is not null and lower(o.email) = lower(c.email) and r.created_at >= :cutoff)""")
                .param("now", Timestamp.from(now))
                .param("cutoff", cutoff)
                .update();
        log.info("Retention: anonymised {} client(s) with no request in {} days", anonymised, keep.toDays());
        return anonymised;
    }

    /** Daily, with retention on (the default); tests call {@link #run} directly. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty("lookmate.retention.enabled")
    static class Job {

        private final ClientContactRetention retention;

        Job(ClientContactRetention retention) {
            this.retention = retention;
        }

        @Scheduled(cron = "${lookmate.retention.cron:0 15 4 * * *}", zone = "UTC")
        void run() {
            try {
                retention.run(Instant.now());
            } catch (RuntimeException e) {
                log.error("Client contact retention failed", e);
            }
        }
    }
}
