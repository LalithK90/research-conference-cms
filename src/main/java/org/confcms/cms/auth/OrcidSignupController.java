package org.confcms.cms.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.confcms.cms.accesslog.AccessLogService;
import org.confcms.cms.security.CustomOAuth2UserService;
import org.confcms.cms.user.User;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Collections;
import java.util.List;

// Finishes a cold ORCID login that OAuth2LoginFailureHandler redirected here: ORCID never
// supplies an email (confirmed against ORCID's own OIDC discovery document -- not a scope
// this integration failed to request), so a brand-new account can't be auto-created the way
// Google's flow does. This asks for just enough -- an email -- to create one.
@Controller
@RequestMapping("/auth")
@RequiredArgsConstructor
public class OrcidSignupController {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final AccessLogService accessLogService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    @GetMapping("/complete-orcid-signup")
    public String form(HttpServletRequest request, Model model) {
        String orcidId = (String) request.getSession().getAttribute("pendingOrcidSignupId");
        if (orcidId == null) {
            // No pending signup in this session (direct navigation, expired session, or
            // already completed) -- nothing to finish here.
            return "redirect:/login?error";
        }
        model.addAttribute("orcidId", orcidId);
        return "auth/complete_orcid_signup";
    }

    @PostMapping("/complete-orcid-signup")
    public String submit(@RequestParam String email, HttpServletRequest request, HttpServletResponse response,
                          Model model) {
        String orcidId = (String) request.getSession().getAttribute("pendingOrcidSignupId");
        String name = (String) request.getSession().getAttribute("pendingOrcidSignupName");
        if (orcidId == null) {
            return "redirect:/login?error";
        }

        try {
            User user = customOAuth2UserService.completeOrcidSignup(orcidId, name, email);

            request.getSession().removeAttribute("pendingOrcidSignupId");
            request.getSession().removeAttribute("pendingOrcidSignupName");

            List<GrantedAuthority> authorities = Collections.singletonList(
                    new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
            var authentication = new UsernamePasswordAuthenticationToken(user.getEmail(), null, authorities);

            // Session fixation protection, matching MagicLinkAuthenticationFilter's own manual
            // SecurityContext persistence -- this request isn't going through Spring Security's
            // own authentication filter chain, so nothing else renames the session here.
            request.changeSessionId();

            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            accessLogService.logLogin(user, request);

            return "redirect:/dashboard";
        } catch (IllegalArgumentException e) {
            model.addAttribute("orcidId", orcidId);
            model.addAttribute("error", e.getMessage());
            return "auth/complete_orcid_signup";
        }
    }
}
