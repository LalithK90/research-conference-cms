package org.confcms.cms.integration;

import org.confcms.cms.auth.AuthService;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.useridentity.UserIdentityRepository;
import org.junit.jupiter.api.Test;

import jakarta.annotation.Resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthServiceIntegrationTest extends IntegrationTest {

    @Resource
    private AuthService authService;
    @Resource
    private UserRepository userRepository;
    @Resource
    private UserIdentityRepository userIdentityRepository;

    @Test
    void registerUserPersistsUserAndLocalIdentityTogether() {
        User saved = authService.registerUser("integration-author@example.com", "Passw0rd!", "Integration Author", Role.AUTHOR);

        User reloaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getEmail()).isEqualTo("integration-author@example.com");
        assertThat(reloaded.getRole()).isEqualTo(Role.AUTHOR);
        assertThat(userIdentityRepository.existsByUserIdAndProvider(saved.getId(), "local")).isTrue();
    }

    @Test
    void registerUserRejectsADuplicateEmailAgainstWhatIsActuallyStored() {
        authService.registerUser("integration-dup@example.com", "Passw0rd!", "First", Role.AUTHOR);

        assertThatThrownBy(() -> authService.registerUser("integration-dup@example.com", "Different1!", "Second", Role.AUTHOR))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(userRepository.findByEmail("integration-dup@example.com")).hasValueSatisfying(
                user -> assertThat(user.getFullName()).isEqualTo("First"));
    }

    @Test
    void updateRoleRefusesToDemoteTheLastEnabledAdmin() {
        // Demote every admin FirstRunAdminInitializer or other tests may have left behind,
        // so this test's admin is unambiguously the only one left -- otherwise the real
        // "last enabled admin" could be a different row and the assertion below would be
        // checking the wrong thing.
        userRepository.findByRole(Role.ADMIN).forEach(existing -> existing.setEnabled(false));

        User admin = authService.registerUser("integration-admin@example.com", "Passw0rd!", "Only Admin", Role.ADMIN);

        assertThatThrownBy(() -> authService.updateRole(admin.getId(), Role.AUTHOR))
                .isInstanceOf(IllegalStateException.class);

        assertThat(userRepository.findById(admin.getId()).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }
}
