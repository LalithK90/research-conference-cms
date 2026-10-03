package org.confcms.cms.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.confcms.cms.user.User;
import org.confcms.cms.useridentity.UserIdentityRepository;
import org.confcms.cms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@RequiredArgsConstructor
public class AccountSettingsController {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;

    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }

    @GetMapping("/account")
    public String show(HttpServletRequest request, Model model) {
        User user = actingUser();
        model.addAttribute("user", user);
        model.addAttribute("identities", userIdentityRepository.findByUserId(user.getId()));
        var session = request.getSession(false);
        model.addAttribute("passwordPromptPending", session != null && session.getAttribute("passwordPromptPending") != null);
        return "account_settings";
    }

    // POST, not GET: dismissal mutates session state, and a state-changing GET is
    // link-prefetchable -- a browser or proxy prefetching the href could silently
    // dismiss the prompt without the user ever clicking it.
    @PostMapping("/account/dismiss-password-prompt")
    public String dismissPasswordPrompt(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.removeAttribute("passwordPromptPending");
        }
        return "redirect:/account";
    }
}
