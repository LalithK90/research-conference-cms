package org.confcms.cms.security;

import org.confcms.cms.accesslog.AccessLogService;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AccessLogService accessLogService;

    private CustomUserDetailsService service;

    @BeforeEach
    void setUp() {
        service = new CustomUserDetailsService(userRepository, accessLogService);
        ReflectionTestUtils.setField(service, "maxLoginFailures", 5);
        ReflectionTestUtils.setField(service, "lockoutWindowMinutes", 15);
    }

    @Test
    void loadsAValidNonLockedOutUserNormally() {
        User user = new User();
        user.setEmail("author@example.com");
        user.setPasswordHash("hashed");
        user.setEnabled(true);
        user.setRole(Role.AUTHOR);
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));
        when(accessLogService.isLoginLockedOut("author@example.com", 5, Duration.ofMinutes(15))).thenReturn(false);

        UserDetails details = service.loadUserByUsername("author@example.com");

        assertThat(details.getUsername()).isEqualTo("author@example.com");
        assertThat(details.isAccountNonLocked()).isTrue();
    }

    @Test
    void rejectsAnAccountLockedOutByRepeatedFailuresBeforeCheckingCredentials() {
        when(accessLogService.isLoginLockedOut("victim@example.com", 5, Duration.ofMinutes(15))).thenReturn(true);

        assertThatThrownBy(() -> service.loadUserByUsername("victim@example.com"))
                .isInstanceOf(org.springframework.security.authentication.LockedException.class);

        // The lockout check happens before any user lookup -- verifies "before checking
        // credentials" isn't just true by accident of mock setup.
        org.mockito.Mockito.verifyNoInteractions(userRepository);
    }

    @Test
    void anUnknownEmailStillGoesThroughTheLockoutCheckSoItsNotADistinguishingOracle() {
        when(accessLogService.isLoginLockedOut("nobody@example.com", 5, Duration.ofMinutes(15))).thenReturn(true);

        assertThatThrownBy(() -> service.loadUserByUsername("nobody@example.com"))
                .isInstanceOf(org.springframework.security.authentication.LockedException.class);
    }

    @Test
    void anUnknownEmailNotLockedOutStillThrowsUsernameNotFound() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(accessLogService.isLoginLockedOut("nobody@example.com", 5, Duration.ofMinutes(15))).thenReturn(false);

        assertThatThrownBy(() -> service.loadUserByUsername("nobody@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
