package org.confcms.cms.web.controller;

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
    public String show(Model model) {
        User user = actingUser();
        model.addAttribute("user", user);
        model.addAttribute("identities", userIdentityRepository.findByUserId(user.getId()));
        return "account_settings";
    }
}
