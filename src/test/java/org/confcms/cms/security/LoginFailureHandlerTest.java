package org.confcms.cms.security;

import org.confcms.cms.accesslog.AccessLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LoginFailureHandlerTest {

    @Mock
    private AccessLogService accessLogService;

    private LoginFailureHandler handler;

    @BeforeEach
    void setUp() {
        handler = new LoginFailureHandler(accessLogService);
    }

    @Test
    void recordsTheAttemptedEmailOnBadCredentialsAndRedirectsToTheGenericError() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("username", "victim@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("nope"));

        verify(accessLogService).logLoginFailure("victim@example.com", request);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
    }

    // Deliberately the SAME redirect and SAME recording behavior as bad credentials -- revealing
    // "this account is locked" distinctly from "wrong password" would itself tell an attacker
    // their guesses are landing on a real, actively-guessed account.
    @Test
    void recordsAndRedirectsIdenticallyWhenTheAccountIsAlreadyLockedOut() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("username", "victim@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new LockedException("locked"));

        verify(accessLogService).logLoginFailure("victim@example.com", request);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
    }

    @Test
    void doesNotRecordWhenNoUsernameWasSubmitted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationFailure(request, response, new BadCredentialsException("nope"));

        verify(accessLogService, never()).logLoginFailure(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
