package org.confcms.cms.security;

import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.AccessLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordPromptAuthenticationSuccessHandlerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AccessLogService accessLogService;

    private PasswordPromptAuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PasswordPromptAuthenticationSuccessHandler(userRepository, accessLogService);
        // Only the login-logging tests care about this lookup; stub leniently so the
        // markPromptIfPasswordless-only tests aren't penalized by Mockito's strict-stubs check.
        lenient().when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
    }

    @Test
    void setsSessionAttributeForPasswordlessUser() {
        User passwordless = new User();
        passwordless.setEmail("passwordless@example.com");
        passwordless.setPasswordHash(null);
        when(userRepository.findByEmail("passwordless@example.com")).thenReturn(Optional.of(passwordless));

        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.markPromptIfPasswordless(request, "passwordless@example.com");

        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getAttribute("passwordPromptPending")).isEqualTo(true);
    }

    @Test
    void setsSessionAttributeForUserWithEmptyStringPasswordHash() {
        User emptyHash = new User();
        emptyHash.setEmail("emptyhash@example.com");
        emptyHash.setPasswordHash("");
        when(userRepository.findByEmail("emptyhash@example.com")).thenReturn(Optional.of(emptyHash));

        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.markPromptIfPasswordless(request, "emptyhash@example.com");

        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getAttribute("passwordPromptPending")).isEqualTo(true);
    }

    @Test
    void doesNotSetSessionAttributeForUserWithPassword() {
        User withPassword = new User();
        withPassword.setEmail("hasone@example.com");
        withPassword.setPasswordHash("hashed");
        when(userRepository.findByEmail("hasone@example.com")).thenReturn(Optional.of(withPassword));

        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.markPromptIfPasswordless(request, "hasone@example.com");

        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void onAuthenticationSuccessLogsTheLoginWhenTheUserIsFound() throws Exception {
        User user = new User();
        user.setEmail("author@example.com");
        user.setPasswordHash("hashed");
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        var authentication = new UsernamePasswordAuthenticationToken("author@example.com", null, Collections.emptyList());

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(accessLogService).logLogin(user, request);
    }

    @Test
    void onAuthenticationSuccessStillRedirectsWhenUserLookupFindsNothing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        var authentication = new UsernamePasswordAuthenticationToken("ghost@example.com", null, Collections.emptyList());

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/dashboard");
    }

    @Test
    void logLoginCanBeCalledDirectlyByMagicLinkAuthenticationFilter() {
        // MagicLinkAuthenticationFilter bypasses onAuthenticationSuccess entirely and calls this
        // package-visible method directly -- this is the fix for magic-link logins never
        // reaching the access log.
        User user = new User();
        user.setEmail("passwordless-login@example.com");
        when(userRepository.findByEmail("passwordless-login@example.com")).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.logLogin(request, "passwordless-login@example.com");

        verify(accessLogService).logLogin(user, request);
    }

    @Test
    void logLoginDoesNothingWhenUserLookupFindsNothing() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.logLogin(request, "ghost@example.com");

        verify(accessLogService, org.mockito.Mockito.never()).logLogin(any(), any());
    }
}
