package org.confcms.cms.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final UserRepository userRepository;

    @GetMapping("/dashboard")
    public String dashboard(HttpServletRequest request, Model model) {
        User user = currentUser();
        if (user.getRole() == Role.ADMIN) {
            return "redirect:/admin/dashboard";
        }
        model.addAttribute("user", user);
        model.addAttribute("passwordPromptPending", request.getSession().getAttribute("passwordPromptPending") != null);
        return "dashboard";
    }

    private User currentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
