package org.confcms.cms.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public class MagicLinkAuthenticationToken extends AbstractAuthenticationToken {

    private final Object principal;
    private final String magicLinkToken;

    /** Unauthenticated: carries only the raw magic-link token string to be verified. */
    public MagicLinkAuthenticationToken(String magicLinkToken) {
        super(null);
        this.principal = null;
        this.magicLinkToken = magicLinkToken;
        setAuthenticated(false);
    }

    /** Authenticated: carries the resolved principal (email) and granted authorities. */
    public MagicLinkAuthenticationToken(Object principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.magicLinkToken = null;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return magicLinkToken;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }
}
