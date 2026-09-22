package org.confcms.cms.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

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
        model.addAttribute("passwordPromptPending", request.getSession().getAttribute("passwordPromptPending") != null);
        return "account_settings";
    }

    @GetMapping("/account/dismiss-password-prompt")
    public String dismissPasswordPrompt(HttpServletRequest request) {
        request.getSession().removeAttribute("passwordPromptPending");
        return "redirect:/account";
    }
}
