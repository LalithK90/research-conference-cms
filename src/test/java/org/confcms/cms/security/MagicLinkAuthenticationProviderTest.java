package org.confcms.cms.security;

import org.confcms.cms.auth.service.MagicLinkService;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.MagicLink;
import org.confcms.cms.domain.User;
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
    void supportsMagicLinkAuthenticationTokenOnly() {
        assertThat(provider.supports(MagicLinkAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.class)).isFalse();
    }
}
