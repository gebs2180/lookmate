package com.lookmate.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.lookmate.app.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.context.NestedTestConfiguration;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The limiter's client address over real HTTP (Tomcat's RemoteIpValve, as in production): X-Forwarded-For counts only
 * when the connection comes from a trusted proxy. Here the test client is always 127.0.0.1.
 */
class ForwardedForTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static int get(int port, String forwardedFor) throws Exception {
        String path = "/api/public/match-requests/tok_" + UUID.randomUUID().toString().replace("-", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Forwarded-For", forwardedFor).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "server.forward-headers-strategy=native",
            "server.tomcat.remoteip.internal-proxies=192\\.0\\.2\\.1",
            "lookmate.rate-limit.other.requests=2",
            "lookmate.rate-limit.other.per=1m"})
    @Import(TestcontainersConfiguration.class)
    @Testcontainers(disabledWithoutDocker = true)
    class FromAClient {

        @LocalServerPort
        int port;

        @Test
        void aSpoofedHeaderIsIgnored() throws Exception {
            // Each request claims another address; the limiter still sees 127.0.0.1, which isn't a trusted proxy here.
            assertThat(get(port, "203.0.113.61")).isEqualTo(404);
            assertThat(get(port, "203.0.113.62")).isEqualTo(404);
            assertThat(get(port, "203.0.113.63")).isEqualTo(429);
        }
    }

    @Nested
    @NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "server.forward-headers-strategy=native",
            "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1",
            "lookmate.rate-limit.other.requests=2",
            "lookmate.rate-limit.other.per=1m"})
    @Import(TestcontainersConfiguration.class)
    @Testcontainers(disabledWithoutDocker = true)
    class FromTheProxy {

        @LocalServerPort
        int port;

        @Test
        void theForwardedAddressIsTheClient() throws Exception {
            assertThat(get(port, "203.0.113.71")).isEqualTo(404);
            assertThat(get(port, "203.0.113.71")).isEqualTo(404);
            assertThat(get(port, "203.0.113.71")).isEqualTo(429);
            assertThat(get(port, "203.0.113.72")).as("another client behind the same proxy").isEqualTo(404);
            // Caddy drops a client's own X-Forwarded-For; were one passed on, the valve takes the right-most address
            // that isn't a trusted proxy, which is the one the proxy added.
            assertThat(get(port, "198.51.100.1, 203.0.113.71")).isEqualTo(429);
        }
    }

    /** The production settings (application.yml, prod profile), resolved the way Spring resolves them. */
    @Test
    void productionTrustsOnlyPrivateProxyAddresses() throws Exception {
        var docs = new YamlPropertySourceLoader().load("application.yml",
                new FileSystemResource(Path.of("src/main/resources/application.yml")));
        PropertySource<?> prod = docs.stream()
                .filter(d -> "prod".equals(d.getProperty("spring.config.activate.on-profile")))
                .findFirst().orElseThrow();
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(prod);

        assertThat(env.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        Pattern trusted = Pattern.compile(env.getProperty("server.tomcat.remoteip.internal-proxies"));
        for (String docker : new String[] {"172.18.0.4", "172.31.255.2", "10.0.3.7", "192.168.32.2"}) {
            assertThat(trusted.matcher(docker).matches()).as(docker).isTrue();
        }
        for (String other : new String[] {"203.0.113.9", "127.0.0.1", "172.32.0.1", "172.15.0.1", "8.8.8.8", "1.10.0.0.1"}) {
            assertThat(trusted.matcher(other).matches()).as(other).isFalse();
        }
        assertThat(Files.readString(Path.of("../../deploy/compose.prod.yml")))
                .as("the backend must not be reachable except through Caddy")
                .doesNotContainPattern("(?s)\n  backend:.*?\n    ports:.*?\n  caddy:");
    }
}
