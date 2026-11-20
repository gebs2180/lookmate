package com.lookmate.app.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BookingLinksTest {

    private static final UUID MATCH = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String UTM = "utm_source=lookmate&utm_medium=referral&utm_campaign=columbus-oh"
            + "&utm_content=11111111-2222-3333-4444-555555555555";

    @Test
    void addsTheTagsToALinkWithoutAQuery() {
        assertThat(BookingLinks.tagged("https://booksy.com/en-us/123_curl-studio", "columbus-oh", MATCH))
                .hasValueSatisfying(u -> assertThat(u.toString())
                        .isEqualTo("https://booksy.com/en-us/123_curl-studio?" + UTM));
    }

    @Test
    void keepsTheStylistsOwnParametersAndFragment() {
        assertThat(BookingLinks.tagged("https://www.vagaro.com/curlstudio?staff=42&ref=ig%20bio#book", "columbus-oh", MATCH))
                .hasValueSatisfying(u -> assertThat(u.toString())
                        .isEqualTo("https://www.vagaro.com/curlstudio?staff=42&ref=ig%20bio&" + UTM + "#book"));
    }

    @Test
    void replacesUtmTagsAlreadyInTheLink() {
        assertThat(BookingLinks.tagged("https://glossgenius.com/x?utm_source=instagram&UTM_CAMPAIGN=old&a=1", "columbus-oh", MATCH))
                .hasValueSatisfying(u -> assertThat(u.toString()).isEqualTo("https://glossgenius.com/x?a=1&" + UTM));
    }

    @Test
    void addsAPathWhenThereIsNone() {
        assertThat(BookingLinks.tagged("https://styleseat.com", "columbus-oh", MATCH))
                .hasValueSatisfying(u -> assertThat(u.toString()).isEqualTo("https://styleseat.com/?" + UTM));
    }

    @Test
    void acceptsAnUppercaseScheme() {
        assertThat(BookingLinks.tagged("HTTPS://booksy.com/a", "columbus-oh", MATCH)).isPresent();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "http://booksy.com/a",              // not https
            "javascript:alert(1)",
            "data:text/html,hi",
            "//evil.example/a",                 // scheme-relative
            "/relative/path",
            "booksy.com/a",                     // no scheme
            "https:///nohost",
            "https://user:pass@evil.example/",  // user info, the classic "looks like booksy.com" trick
            "https://booksy.com/a b",           // not a valid URI
            "ftp://booksy.com/a",
            "   "})
    void refusesAnythingButAPlainHttpsLink(String url) {
        assertThat(BookingLinks.tagged(url, "columbus-oh", MATCH)).isEmpty();
    }
}
