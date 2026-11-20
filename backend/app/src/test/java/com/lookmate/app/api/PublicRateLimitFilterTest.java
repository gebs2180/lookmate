package com.lookmate.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PublicRateLimitFilterTest {

    private final PublicRateLimitFilter filter = new PublicRateLimitFilter(
            new RateLimitProperties(true, null, null, null, null, null, 100), (req, res, h, e) -> null, JsonMapper.builder().build(),
            () -> 0L);

    private String emailKey(String json) {
        return filter.emailKey(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void ipv4IsKeptAndIpv6CountsPerSlash64() {
        assertThat(PublicRateLimitFilter.clientKey("203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(PublicRateLimitFilter.clientKey("2001:db8:1:2::1"))
                .isEqualTo(PublicRateLimitFilter.clientKey("2001:0db8:0001:0002:ffff:ffff:ffff:ffff"))
                .isNotEqualTo(PublicRateLimitFilter.clientKey("2001:db8:1:3::1"));
        assertThat(PublicRateLimitFilter.clientKey("::ffff:203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(PublicRateLimitFilter.clientKey("not-an-address")).isEqualTo("not-an-address");
    }

    @Test
    void emailsAreHashedAndNormalized() {
        String key = emailKey("{\"email\":\"zoe@example.com\"}");
        assertThat(key).hasSize(64).doesNotContain("zoe");
        assertThat(emailKey("{\"email\":\"  Zoe+Hair@EXAMPLE.com \"}")).isEqualTo(key);
        assertThat(emailKey("{\"email\":\"zoe2@example.com\"}")).isNotEqualTo(key);
        // The controller binds the last of two "email" fields, and so does this.
        assertThat(emailKey("{\"email\":\"x@example.com\",\"email\":\"zoe@example.com\"}")).isEqualTo(key);
    }

    @Test
    void noEmailNoKey() {
        assertThat(emailKey("{}")).isNull();
        assertThat(emailKey("{\"email\":42}")).isNull();
        assertThat(emailKey("{\"email\":\" \"}")).isNull();
        assertThat(emailKey("not json")).isNull();
        assertThat(emailKey("[]")).isNull();
    }
}
