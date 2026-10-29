package org.confcms.cms.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HexColorValidatorTest {

    @Test
    void acceptsASixDigitHexColor() {
        assertThat(HexColorValidator.isHexOrBlank("#06690f")).isTrue();
    }

    @Test
    void acceptsAThreeDigitHexColor() {
        assertThat(HexColorValidator.isHexOrBlank("#fff")).isTrue();
    }

    @Test
    void acceptsNull() {
        assertThat(HexColorValidator.isHexOrBlank(null)).isTrue();
    }

    @Test
    void acceptsBlank() {
        assertThat(HexColorValidator.isHexOrBlank("  ")).isTrue();
    }

    @Test
    void rejectsAValueWithoutAHash() {
        assertThat(HexColorValidator.isHexOrBlank("06690f")).isFalse();
    }

    @Test
    void rejectsCssInjectionAttemptingToCloseTheStyleBlock() {
        assertThat(HexColorValidator.isHexOrBlank("red; } body { display: none !important; } .x {")).isFalse();
    }

    @Test
    void rejectsAnUnsupportedColorKeyword() {
        assertThat(HexColorValidator.isHexOrBlank("red")).isFalse();
    }
}
