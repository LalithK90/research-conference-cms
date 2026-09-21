package org.confcms.cms.auth.service;

import org.confcms.cms.domain.PasswordResetToken;
import org.confcms.cms.domain.User;
import org.confcms.cms.domain.UserIdentity;
import org.confcms.cms.repository.PasswordResetTokenRepository;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordResetTokenRepository tokenRepository;
    @Mock
    private UserIdentityRepository userIdentityRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private PasswordEncoder passwordEncoder;

    private PasswordResetService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(userRepository, tokenRepository, userIdentityRepository, emailService, passwordEncoder);

        user = new User();
        user.setId(1L);
        user.setEmail("author@example.com");
    }

    @Test
    void requestResetForExistingEmailCreatesTokenAndSendsEmail() {
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.empty());
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestReset("author@example.com");

        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
        assertThat(captor.getValue().isUsed()).isFalse();
        assertThat(captor.getValue().getExpiresAt()).isAfter(LocalDateTime.now());

        verify(emailService).sendSimpleEmail(eq("author@example.com"), any(), any());
    }

    @Test
    void requestResetForNonexistentEmailDoesNotThrowAndSendsNoEmail() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        service.requestReset("nobody@example.com");

        verify(emailService, never()).sendSimpleEmail(any(), any(), any());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void requestResetWithinCooldownDoesNotSendSecondEmail() {
        PasswordResetToken recent = new PasswordResetToken();
        recent.setUser(user);
        recent.setExpiresAt(LocalDateTime.now().plusHours(2));
        recent.setUsed(false);
        // createdAt is set by JPA auditing in production; simulate a recent token by
        // making requestReset's cooldown check rely on expiresAt/used, not createdAt,
        // since createdAt is not settable pre-persist in a unit test -- see Step 5 note.

        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(recent));

        service.requestReset("author@example.com");

        verify(emailService, never()).sendSimpleEmail(any(), any(), any());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void validateTokenRejectsUnknownToken() {
        when(tokenRepository.findByToken("bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateToken("bad-token"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateTokenRejectsExpiredToken() {
        PasswordResetToken expired = new PasswordResetToken();
        expired.setUser(user);
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        expired.setUsed(false);
        when(tokenRepository.findByToken("expired-token")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.validateToken("expired-token"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateTokenRejectsUsedToken() {
        PasswordResetToken used = new PasswordResetToken();
        used.setUser(user);
        used.setExpiresAt(LocalDateTime.now().plusHours(1));
        used.setUsed(true);
        when(tokenRepository.findByToken("used-token")).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> service.validateToken("used-token"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resetPasswordSetsHashMarksTokenUsedAndEnsuresLocalIdentity() {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setToken("valid-token");
        token.setExpiresAt(LocalDateTime.now().plusHours(1));
        token.setUsed(false);

        when(tokenRepository.findByToken("valid-token")).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("NewPassw0rd!")).thenReturn("hashed-value");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userIdentityRepository.existsByUserIdAndProvider(1L, "local")).thenReturn(false);
        when(userIdentityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resetPassword("valid-token", "NewPassw0rd!");

        assertThat(user.getPasswordHash()).isEqualTo("hashed-value");
        assertThat(token.isUsed()).isTrue();
        verify(tokenRepository).save(token);

        ArgumentCaptor<UserIdentity> identityCaptor = ArgumentCaptor.forClass(UserIdentity.class);
        verify(userIdentityRepository).save(identityCaptor.capture());
        assertThat(identityCaptor.getValue().getProvider()).isEqualTo("local");
        assertThat(identityCaptor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void resetPasswordDoesNotDuplicateExistingLocalIdentity() {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setToken("valid-token");
        token.setExpiresAt(LocalDateTime.now().plusHours(1));
        token.setUsed(false);

        when(tokenRepository.findByToken("valid-token")).thenReturn(Optional.of(token));
        when(passwordEncoder.encode(any())).thenReturn("hashed-value");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userIdentityRepository.existsByUserIdAndProvider(1L, "local")).thenReturn(true);

        service.resetPassword("valid-token", "NewPassw0rd!");

        verify(userIdentityRepository, never()).save(any());
    }
}
