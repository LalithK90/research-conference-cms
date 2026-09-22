package org.confcms.cms.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MagicLinkAuthenticationFilterTest {

    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private FilterChain filterChain;
    @Mock
    private Authentication authenticatedResult;

    private MagicLinkAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new MagicLinkAuthenticationFilter(authenticationManager);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void nonVerifyPathPassesThroughUnaffected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login");
        request.setServletPath("/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(authenticationManager);
    }

    @Test
    void missingTokenRedirectsWithoutAuthenticating() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/magic/verify");
        request.setServletPath("/auth/magic/verify");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=magic-link-missing-token");
        verifyNoInteractions(authenticationManager);
    }

    @Test
    void invalidTokenRedirectsWithErrorAndDoesNotEstablishASession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/magic/verify");
        request.setServletPath("/auth/magic/verify");
        request.setParameter("token", "bad-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(authenticationManager.authenticate(any(MagicLinkAuthenticationToken.class)))
                .thenThrow(new BadCredentialsException("invalid"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error=magic-link-invalid");
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void validTokenPersistsContextAndRedirectsToDashboard() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/magic/verify");
        request.setServletPath("/auth/magic/verify");
        request.setParameter("token", "good-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(authenticationManager.authenticate(any(MagicLinkAuthenticationToken.class)))
                .thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getRedirectedUrl()).isEqualTo("/dashboard");
        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getAttribute("SPRING_SECURITY_CONTEXT")).isNotNull();
    }

    @Test
    void validTokenRenamesAnExistingSessionId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/magic/verify");
        request.setServletPath("/auth/magic/verify");
        request.setParameter("token", "good-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Simulate a pre-existing (pre-authentication) session -- exactly the state an attacker
        // could plant on a victim via a known session ID before the victim clicks the link.
        String preAuthSessionId = request.getSession(true).getId();

        when(authenticationManager.authenticate(any(MagicLinkAuthenticationToken.class)))
                .thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        String postAuthSessionId = request.getSession(false).getId();
        assertThat(postAuthSessionId).isNotEqualTo(preAuthSessionId);
    }

    @Test
    void doesNotAttemptToRenameASessionThatDidNotExistYet() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/magic/verify");
        request.setServletPath("/auth/magic/verify");
        request.setParameter("token", "good-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(request.getSession(false)).isNull();

        when(authenticationManager.authenticate(any(MagicLinkAuthenticationToken.class)))
                .thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(request.getSession(false)).isNotNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/dashboard");
    }
}
