package com.lookmate.app.stylists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ProfileFieldRulesTest {

    @ParameterizedTest
    @CsvSource({
            "https://booksy.com/en-us/123_curl-studio, BOOKSY",
            "https://www.vagaro.com/curlstudio, VAGARO",
            "https://www.styleseat.com/m/v/curlstudio, STYLESEAT",
            "https://curlstudio.glossgenius.com, GLOSSGENIUS",
            "https://curl-studio.square.site, SQUARE",
            "https://book.squareup.com/appointments/abc, SQUARE",
            "https://curlstudio.com/book, OTHER",
            // Look-alike hosts are not the platform.
            "https://booksy.com.evil.example/x, OTHER",
            "https://notbooksy.com/x, OTHER",
    })
    void detectsTheBookingPlatformFromTheHost(String url, BookingPlatform platform) {
        assertThat(ProfileFieldRules.platform(ProfileFieldRules.bookingUrl(url))).isEqualTo(platform);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://booksy.com/x", "booksy.com/x", "javascript:alert(1)", "https://user:pw@booksy.com/x",
            "https://localhost/x", "ftp://booksy.com", "https:// spaced.com"})
    void refusesBookingLinksThatArentPlainHttps(String url) {
        assertThatThrownBy(() -> ProfileFieldRules.bookingUrl(url)).isInstanceOf(ProfileFieldRules.Invalid.class);
    }

    @Test
    void aBlankBookingLinkIsNone() {
        assertThat(ProfileFieldRules.bookingUrl("  ")).isNull();
        assertThat(ProfileFieldRules.platform(null)).isNull();
    }

    @ParameterizedTest
    @CsvSource({"@curl.studio, curl.studio", "curl_studio, curl_studio", "https://www.instagram.com/curl.studio/?hl=en, curl.studio",
            "instagram.com/curlstudio, curlstudio"})
    void normalisesInstagramHandles(String raw, String handle) {
        assertThat(ProfileFieldRules.instagram(raw)).isEqualTo(handle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"curl studio", "@", "<script>", "a234567890123456789012345678901"})
    void refusesWhatIsntAHandle(String raw) {
        assertThatThrownBy(() -> ProfileFieldRules.instagram(raw)).isInstanceOf(ProfileFieldRules.Invalid.class);
    }

    @ParameterizedTest
    @CsvSource({"Ana-Curls, ana-curls", "https://combpatible.app/s/ana-curls/, ana-curls", "combpatible.app/s/jo2, jo2",
            "'  abc  ', abc"})
    void normalisesPageAddresses(String raw, String slug) {
        assertThat(ProfileFieldRules.slug(raw)).isEqualTo(slug);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "-ana", "ana-", "ana curls", "ana_curls", "a2345678901234567890123456789012345678901", "<script>"})
    void refusesWhatIsntAPageAddress(String raw) {
        assertThatThrownBy(() -> ProfileFieldRules.slug(raw)).isInstanceOf(ProfileFieldRules.Invalid.class);
    }

    @ParameterizedTest
    @CsvSource({"Ana María López, ana-maria-lopez", "'  Curl Studio by Ana!! ', curl-studio-by-ana", "Jo, jo-stylist",
            "'!!!', stylist", "Ẑoë O'Neil-Smith, zoe-o-neil-smith",
            "A Very Long Salon Name That Goes On And On Forever, a-very-long-salon-name-that-goes-o"})
    void makesAnAddressFromTheName(String name, String slug) {
        assertThat(ProfileFieldRules.slugFrom(name)).isEqualTo(slug);
        assertThat(ProfileFieldRules.slug(slug)).isEqualTo(slug);
    }
}
