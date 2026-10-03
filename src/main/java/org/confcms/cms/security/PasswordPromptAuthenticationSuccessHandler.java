package org.confcms.cms.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.service.AccessLogService;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class PasswordPromptAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final AccessLogService accessLogService;

    public PasswordPromptAuthenticationSuccessHandler(UserRepository userRepository, AccessLogService accessLogService) {
        this.userRepository = userRepository;
        this.accessLogService = accessLogService;
        setDefaultTargetUrl("/dashboard");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        markPromptIfPasswordless(request, authentication.getName());
        logLogin(request, authentication.getName());
        super.onAuthenticationSuccess(request, response, authentication);
    }

    void markPromptIfPasswordless(HttpServletRequest request, String email) {
        userRepository.findByEmail(email)
                .filter(user -> user.getPasswordHash() == null || user.getPasswordHash().isBlank())
                .ifPresent(user -> request.getSession().setAttribute("passwordPromptPending", true));
    }

    // Package-visible so MagicLinkAuthenticationFilter can call it directly -- that filter
    // bypasses Spring Security's success-handler mechanism entirely (it persists the
    // SecurityContext manually and redirects itself), so onAuthenticationSuccess above is
    // never reached for a magic-link login. Without this being called separately, magic-link
    // logins -- the passwordless path -- would never appear in the access log at all.
    void logLogin(HttpServletRequest request, String email) {
        userRepository.findByEmail(email)
                .ifPresent(user -> accessLogService.logLogin(user, request));
    }
}
