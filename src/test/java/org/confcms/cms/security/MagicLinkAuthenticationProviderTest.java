package org.confcms.cms.security;

import org.confcms.cms.auth.MagicLinkService;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.auth.MagicLink;
import org.confcms.cms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MagicLinkAuthenticationProviderTest {

    @Mock
    private MagicLinkService magicLinkService;

    private MagicLinkAuthenticationProvider provider;
    private User user;

    @BeforeEach
    void setUp() {
        provider = new MagicLinkAuthenticationProvider(magicLinkService);

        user = new User();
        user.setEmail("author@example.com");
        user.setRole(Role.AUTHOR);
    }

    @Test
    void authenticateRejectsUnknownToken() {
        when(magicLinkService.findByToken("bad-token")).thenReturn(Optional.empty());

        Authentication unauth = new MagicLinkAuthenticationToken("bad-token");

        assertThatThrownBy(() -> provider.authenticate(unauth))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticateRejectsUsedToken() {
        MagicLink link = new MagicLink();
        link.setUser(user);
        link.setExpiresAt(LocalDateTime.now().plusHours(1));
        link.setUsed(true);
        when(magicLinkService.findByToken("used-token")).thenReturn(Optional.of(link));

        Authentication unauth = new MagicLinkAuthenticationToken("used-token");

        assertThatThrownBy(() -> provider.authenticate(unauth))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticateRejectsExpiredToken() {
        MagicLink link = new MagicLink();
        link.setUser(user);
        link.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        link.setUsed(false);
        when(magicLinkService.findByToken("expired-token")).thenReturn(Optional.of(link));

        Authentication unauth = new MagicLinkAuthenticationToken("expired-token");

        assertThatThrownBy(() -> provider.authenticate(unauth))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticateValidTokenMarksUsedAndReturnsAuthenticatedToken() {
        MagicLink link = new MagicLink();
        link.setUser(user);
        link.setExpiresAt(LocalDateTime.now().plusHours(1));
        link.setUsed(false);
        when(magicLinkService.findByToken("good-token")).thenReturn(Optional.of(link));
        when(magicLinkService.markUsed(link)).thenReturn(true);

        Authentication unauth = new MagicLinkAuthenticationToken("good-token");
        Authentication result = provider.authenticate(unauth);

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isEqualTo("author@example.com");
        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_AUTHOR");
        verify(magicLinkService).markUsed(link);
    }

    @Test
    void authenticateRejectsWhenMarkUsedLosesTheRace() {
        // Two concurrent requests both pass the isUsed()/expiry check on the same token before
        // either writes; only one of them may actually flip the row from unused -> used.
        MagicLink link = new MagicLink();
        link.setUser(user);
        link.setExpiresAt(LocalDateTime.now().plusHours(1));
        link.setUsed(false);
        when(magicLinkService.findByToken("raced-token")).thenReturn(Optional.of(link));
        when(magicLinkService.markUsed(link)).thenReturn(false);

        Authentication unauth = new MagicLinkAuthenticationToken("raced-token");

        assertThatThrownBy(() -> provider.authenticate(unauth))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticateRejectsDisabledUser() {
        user.setEnabled(false);
        MagicLink link = new MagicLink();
        link.setUser(user);
        link.setExpiresAt(LocalDateTime.now().plusHours(1));
        link.setUsed(false);
        when(magicLinkService.findByToken("disabled-user-token")).thenReturn(Optional.of(link));
        when(magicLinkService.markUsed(link)).thenReturn(true);

        Authentication unauth = new MagicLinkAuthenticationToken("disabled-user-token");

        assertThatThrownBy(() -> provider.authenticate(unauth))
                .isInstanceOf(org.springframework.security.authentication.DisabledException.class);
    }

    @Test
    void supportsMagicLinkAuthenticationTokenOnly() {
        assertThat(provider.supports(MagicLinkAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.class)).isFalse();
    }
}
