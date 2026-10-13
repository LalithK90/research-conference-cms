package org.confcms.cms.security;

import org.confcms.cms.accesslog.AccessLogService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

// Records every failed password-login attempt (wrong password, unknown email, or an already
// locked-out account) so CustomUserDetailsService's lockout check has failures to count --
// deliberately the same generic /login?error redirect for all three cases, so neither the
// failure count nor lockout state becomes something an attacker can distinguish from outside.
@Component
public class LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final AccessLogService accessLogService;

    public LoginFailureHandler(AccessLogService accessLogService) {
        super("/login?error");
        this.accessLogService = accessLogService;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException, ServletException {
        String email = request.getParameter("username");
        if (email != null && !email.isBlank()) {
            accessLogService.logLoginFailure(email, request);
        }
        super.onAuthenticationFailure(request, response, exception);
    }
}
