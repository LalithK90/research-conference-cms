package org.confcms.cms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class MagicLinkAuthenticationFilter extends OncePerRequestFilter {

    private static final String VERIFY_PATH = "/auth/magic/verify";

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public MagicLinkAuthenticationFilter(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!VERIFY_PATH.equals(request.getServletPath()) && !VERIFY_PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawToken = request.getParameter("token");
        if (rawToken == null || rawToken.isBlank()) {
            response.sendRedirect("/login?error=magic-link-missing-token");
            return;
        }

        try {
            Authentication result = authenticationManager.authenticate(new MagicLinkAuthenticationToken(rawToken));

            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(result);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            response.sendRedirect("/dashboard");
        } catch (BadCredentialsException e) {
            response.sendRedirect("/login?error=magic-link-invalid");
        }
    }
}
