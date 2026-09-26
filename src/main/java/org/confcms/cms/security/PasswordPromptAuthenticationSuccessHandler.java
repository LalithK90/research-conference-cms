package org.confcms.cms.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.confcms.cms.repository.UserRepository;
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
        userRepository.findByEmail(authentication.getName())
                .ifPresent(user -> accessLogService.logLogin(user, request));
        super.onAuthenticationSuccess(request, response, authentication);
    }

    void markPromptIfPasswordless(HttpServletRequest request, String email) {
        userRepository.findByEmail(email)
                .filter(user -> user.getPasswordHash() == null || user.getPasswordHash().isBlank())
                .ifPresent(user -> request.getSession().setAttribute("passwordPromptPending", true));
    }
}
