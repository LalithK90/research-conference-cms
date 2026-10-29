package org.confcms.cms.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeUrlValidatorTest {

    @Test
    void acceptsAnHttpUrl() {
        assertThat(SafeUrlValidator.isHttpOrHttpsOrBlank("http://example.com/logo.png")).isTrue();
    }

    @Test
    void acceptsAnHttpsUrl() {
        assertThat(SafeUrlValidator.isHttpOrHttpsOrBlank("https://example.com/logo.png")).isTrue();
    }

    @Test
    void acceptsNull() {
        assertThat(SafeUrlValidator.isHttpOrHttpsOrBlank(null)).isTrue();
    }

    @Test
    void acceptsBlank() {
        assertThat(SafeUrlValidator.isHttpOrHttpsOrBlank("  ")).isTrue();
    }

    @Test
    void rejectsJavascriptScheme() {
        assertThat(SafeUrlValidator.isHttpOrHttpsOrBlank("javascript:alert(1)")).isFalse();
    }

    @Test
    void rejectsDataScheme() {
        assertThat(SafeUrlValidator.isHttpOrHttpsOrBlank("data:text/html,<script>alert(1)</script>")).isFalse();
    }

    @Test
    void rejectsMalformedUri() {
        assertThat(SafeUrlValidator.isHttpOrHttpsOrBlank("http://[invalid")).isFalse();
    }
}
