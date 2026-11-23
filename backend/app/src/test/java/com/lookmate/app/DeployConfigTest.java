package com.lookmate.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The production edge (deploy/Caddyfile) and images, as the security review of 2026-10-26 left them.
 * Read as text: Caddy itself isn't run here.
 */
class DeployConfigTest {

    /** Tests run in backend/app. */
    private static final Path REPO = Path.of("../..");

    @Test
    void theSiteSendsAContentSecurityPolicy() throws Exception {
        // The SPA keeps its Auth0 refresh tokens in localStorage, so injected script would take over the account,
        // the admin's included. The policy allows our own scripts only.
        String caddy = Files.readString(REPO.resolve("deploy/Caddyfile"));
        Matcher csp = Pattern.compile("Content-Security-Policy \"([^\"]+)\"").matcher(caddy);
        assertThat(csp.find()).as("a Content-Security-Policy header").isTrue();
        String policy = csp.group(1);
        assertThat(policy).contains("default-src 'self'", "script-src 'self'", "object-src 'none'", "base-uri 'self'",
                "frame-ancestors 'none'", "form-action 'self'");
        assertThat(policy).doesNotContain("unsafe-eval");
        assertThat(directive(policy, "script-src")).doesNotContain("unsafe-inline", "*", "data:");
        // Photos come straight from storage (presigned), the login from Auth0.
        assertThat(directive(policy, "img-src")).contains("{$PHOTO_ORIGIN}");
        assertThat(directive(policy, "connect-src")).contains("{$PHOTO_ORIGIN}", "https://{$AUTH0_DOMAIN}");
        assertThat(caddy).contains("Strict-Transport-Security", "X-Content-Type-Options nosniff");
    }

    @Test
    void onlyHealthIsReachableFromActuator() throws Exception {
        String caddy = Files.readString(REPO.resolve("deploy/Caddyfile"));
        assertThat(caddy).contains("handle /actuator/health {").contains("handle /actuator/* {\n\t\trespond 404");
    }

    @Test
    void theApiTakesNoHugeBodies() throws Exception {
        // Photos go straight to storage, so nothing the API takes is large, and any signed-in account can reach a route
        // that parses a body. A 1 GB JSON string would end the JVM (-XX:+ExitOnOutOfMemoryError).
        String caddy = Files.readString(REPO.resolve("deploy/Caddyfile"));
        assertThat(caddy).contains("handle /api/* {\n\t\trequest_body {\n\t\t\tmax_size 1MB\n\t\t}");
    }

    @Test
    void linkTokensStayOutOfTheAccessLog() throws Exception {
        // An invite link is a key to an org for a week and a client's link the key to their request; the tokens are in
        // the path. Review 2026-11-22, #14: the page /invite/<token> was logged in full. Java's regex reads this
        // pattern as Caddy's (Go RE2) does.
        String caddy = Files.readString(REPO.resolve("deploy/Caddyfile"));
        Matcher filter = Pattern.compile("request>uri regexp \"([^\"]+)\" \"([^\"]+)\"").matcher(caddy);
        assertThat(filter.find()).as("a request>uri regexp filter").isTrue();
        Pattern tokenPath = Pattern.compile(filter.group(1));
        String replacement = filter.group(2).replace("${1}", "$1");
        for (String uri : new String[] {"/invite/tok123", "/api/invites/tok123/accept", "/m/tok123",
                "/api/public/match-requests/tok123", "/api/public/book/tok123?s=1", "/api/public/book-stylist/tok123"}) {
            assertThat(tokenPath.matcher(uri).replaceFirst(replacement)).as(uri).doesNotContain("tok123");
        }
        assertThat(tokenPath.matcher("/stylists/abc").find()).isFalse();
    }

    @Test
    void aClientPageSendsNoReferrer() throws Exception {
        // Review 2026-11-22, #46: a click from /m/<token> to a stylist's booking page mustn't carry the token. The
        // site-wide policy is kept off /m/* so it can't overwrite this one.
        String caddy = Files.readString(REPO.resolve("deploy/Caddyfile"));
        assertThat(caddy).contains("header /m/* Referrer-Policy no-referrer",
                "@notClientLink not path /m/*", "header @notClientLink Referrer-Policy strict-origin-when-cross-origin");
        int start = caddy.indexOf("\n\theader {");
        String siteHeaders = caddy.substring(start, caddy.indexOf("\n\t}", start));
        assertThat(siteHeaders).doesNotContain("Referrer-Policy");
    }

    @Test
    void theBackendAndBackupRunnerRunWithoutPrivileges() throws Exception {
        // Review 2026-11-22, #21.
        String compose = Files.readString(REPO.resolve("deploy/compose.prod.yml"));
        String backend = compose.substring(compose.indexOf("\n  backend:"), compose.indexOf("\n  caddy:"));
        String backup = compose.substring(compose.indexOf("\n  backup:"), compose.indexOf("\nvolumes:"));
        for (String service : new String[] {backend, backup}) {
            assertThat(service).contains("security_opt: [no-new-privileges:true]", "cap_drop: [ALL]", "mem_limit:");
        }
        assertThat(backend).contains("read_only: true", "- /tmp");
    }

    @Test
    void theCaddyContainerGetsTheHostsThePolicyNames() throws Exception {
        String compose = Files.readString(REPO.resolve("deploy/compose.prod.yml"));
        String caddy = compose.substring(compose.indexOf("\n  caddy:"), compose.indexOf("\n  backup:"));
        assertThat(caddy).contains("PHOTO_ORIGIN:", "AUTH0_DOMAIN:");
    }

    @Test
    void theApiDoesNotRunAsRoot() throws Exception {
        String dockerfile = Files.readString(REPO.resolve("backend/Dockerfile"));
        String runtime = dockerfile.substring(dockerfile.lastIndexOf("FROM "));
        assertThat(runtime).containsPattern("(?m)^USER (?!root)\\w+");
    }

    @Test
    void caddyAndTheBackupRunnerDoNotRunAsRootEither() throws Exception {
        // Security review 2026-10-26, #8. Caddy is the process facing the internet.
        for (String path : new String[] {"web/Dockerfile", "deploy/backup/Dockerfile"}) {
            String dockerfile = Files.readString(REPO.resolve(path));
            String runtime = dockerfile.substring(dockerfile.lastIndexOf("FROM "));
            assertThat(runtime).as(path).containsPattern("(?m)^USER (?!root|0\\b)\\w+");
        }
        String compose = Files.readString(REPO.resolve("deploy/compose.prod.yml"));
        String caddy = compose.substring(compose.indexOf("\n  caddy:"), compose.indexOf("\n  backup:"));
        assertThat(caddy).contains("cap_drop: [ALL]", "cap_add: [NET_BIND_SERVICE]").doesNotContain("user: root");
        // The deploy's chown of the existing caddy volumes names this uid.
        assertThat(Files.readString(REPO.resolve("web/Dockerfile"))).contains("-u 10001");
    }

    private static String directive(String policy, String name) {
        for (String d : policy.split(";")) {
            if (d.strip().startsWith(name + " ")) {
                return d.strip();
            }
        }
        return "";
    }
}
