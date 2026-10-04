package org.confcms.cms.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2LoginFailureHandlerTest {

    private final OAuth2LoginFailureHandler handler = new OAuth2LoginFailureHandler();

    @Test
    void redirectsToOrcidSignupCompletionAndStashesPendingDataInSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        var exception = new OrcidSignupRequiresEmailException("0000-0002-1825-0097", "New Researcher");

        handler.onAuthenticationFailure(request, response, exception);

        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/complete-orcid-signup");
        assertThat(request.getSession().getAttribute("pendingOrcidSignupId")).isEqualTo("0000-0002-1825-0097");
        assertThat(request.getSession().getAttribute("pendingOrcidSignupName")).isEqualTo("New Researcher");
    }

    @Test
    void fallsBackToGenericLoginErrorForOtherAuthenticationExceptions() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("nope"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(request.getSession().getAttribute("pendingOrcidSignupId")).isNull();
    }
}
