package org.confcms.cms.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordStrengthValidatorTest {

    @Test
    void rejectsNull() {
        assertThat(PasswordStrengthValidator.isStrongEnough(null)).isFalse();
    }

    @Test
    void rejectsTooShort() {
        assertThat(PasswordStrengthValidator.isStrongEnough("Ab1")).isFalse();
    }

    @Test
    void rejectsLettersOnly() {
        assertThat(PasswordStrengthValidator.isStrongEnough("longenoughpassword")).isFalse();
    }

    @Test
    void rejectsDigitsOnly() {
        assertThat(PasswordStrengthValidator.isStrongEnough("12345678")).isFalse();
    }

    @Test
    void acceptsLettersAndDigitsAtMinLength() {
        assertThat(PasswordStrengthValidator.isStrongEnough("Passw0rd")).isTrue();
    }

    @Test
    void acceptsLongerMixedPassword() {
        assertThat(PasswordStrengthValidator.isStrongEnough("CorrectHorseBattery9")).isTrue();
    }
}
