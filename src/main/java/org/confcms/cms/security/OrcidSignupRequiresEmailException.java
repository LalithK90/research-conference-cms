package org.confcms.cms.security;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

// Thrown by CustomOAuth2UserService for a cold ORCID login (no existing session, no existing
// UserIdentity) -- ORCID's OAuth2/OIDC flow never returns an email address under any scope
// (confirmed against ORCID's own OIDC discovery document), so there is nothing to create a
// User from yet. OAuth2LoginFailureHandler catches this specific type and redirects to a short
// "confirm your email to finish creating your account" step instead of a generic login error.
public class OrcidSignupRequiresEmailException extends OAuth2AuthenticationException {

    private final String orcidId;
    private final String name;

    public OrcidSignupRequiresEmailException(String orcidId, String name) {
        super(new OAuth2Error("orcid_signup_requires_email"),
                "ORCID did not return an email address; one more step is needed to finish creating your account");
        this.orcidId = orcidId;
        this.name = name;
    }

    public String getOrcidId() {
        return orcidId;
    }

    public String getName() {
        return name;
    }
}
