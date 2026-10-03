package org.confcms.cms.security;

import org.confcms.cms.auth.service.MagicLinkService;
import org.confcms.cms.domain.MagicLink;
import org.confcms.cms.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MagicLinkAuthenticationProvider implements AuthenticationProvider {

    private final MagicLinkService magicLinkService;

    // Without its own transaction, link.getUser() (a lazy Hibernate proxy) throws
    // LazyInitializationException once control returns here from findByToken's own
    // read-only transaction, which has already closed by this point.
    @Transactional
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        MagicLinkAuthenticationToken token = (MagicLinkAuthenticationToken) authentication;
        String rawToken = (String) token.getCredentials();

        MagicLink link = magicLinkService.findByToken(rawToken)
                .orElseThrow(() -> new BadCredentialsException("This link is invalid, expired, or already used"));

        if (link.isUsed() || link.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadCredentialsException("This link is invalid, expired, or already used");
        }

        if (!magicLinkService.markUsed(link)) {
            throw new BadCredentialsException("This link is invalid, expired, or already used");
        }

        User user = link.getUser();
        if (!user.isEnabled()) {
            throw new DisabledException("This account is disabled");
        }

        List<GrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));

        return new MagicLinkAuthenticationToken(user.getEmail(), authorities);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return MagicLinkAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
