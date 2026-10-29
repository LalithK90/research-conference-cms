package org.confcms.cms.integration;

import org.confcms.cms.auth.AuthService;
import org.confcms.cms.auth.PasswordResetService;
import org.confcms.cms.auth.PasswordResetToken;
import org.confcms.cms.auth.PasswordResetTokenRepository;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import org.junit.jupiter.api.Test;

import jakarta.annotation.Resource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Exercises the real password-history reuse check (PasswordHistoryService, wired into
// PasswordResetService.resetPassword) against a real DB and a real BCryptPasswordEncoder --
// the Mockito unit tests for both classes stub passwordEncoder.matches()/history lookups, so
// they can't catch a mismatch between what gets hashed and what gets compared.
class PasswordResetIntegrationTest extends IntegrationTest {

    @Resource
    private AuthService authService;
    @Resource
    private PasswordResetService passwordResetService;
    @Resource
    private PasswordResetTokenRepository tokenRepository;
    @Resource
    private UserRepository userRepository;

    private PasswordResetToken issueToken(User user) {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(LocalDateTime.now().plusHours(2));
        token.setUsed(false);
        return tokenRepository.save(token);
    }

    @Test
    void resetPasswordRejectsReusingTheSamePasswordAcrossTwoResets() {
        User user = authService.registerUser("integration-reset@example.com", "InitialPass1!", "Reset Author", Role.AUTHOR);

        PasswordResetToken firstToken = issueToken(user);
        passwordResetService.resetPassword(firstToken.getToken(), "SecondPass2!");

        PasswordResetToken secondToken = issueToken(user);
        assertThatThrownBy(() -> passwordResetService.resetPassword(secondToken.getToken(), "SecondPass2!"))
                .isInstanceOf(IllegalArgumentException.class);

        // The rejected reset must not have consumed the token -- the user can retry with a
        // different password on the same link.
        PasswordResetToken reloaded = tokenRepository.findByToken(secondToken.getToken()).orElseThrow();
        assertThat(reloaded.isUsed()).isFalse();
    }

    @Test
    void resetPasswordAllowsAGenuinelyNewPasswordAndUpdatesTheStoredHash() {
        User user = authService.registerUser("integration-reset-ok@example.com", "InitialPass1!", "Reset OK Author", Role.AUTHOR);
        String originalHash = user.getPasswordHash();

        PasswordResetToken token = issueToken(user);
        passwordResetService.resetPassword(token.getToken(), "BrandNewPass3!");

        User reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getPasswordHash()).isNotEqualTo(originalHash);

        PasswordResetToken reloadedToken = tokenRepository.findByToken(token.getToken()).orElseThrow();
        assertThat(reloadedToken.isUsed()).isTrue();
    }
}
