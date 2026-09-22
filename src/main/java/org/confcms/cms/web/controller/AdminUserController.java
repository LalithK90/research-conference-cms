package org.confcms.cms.web.controller;

import org.confcms.cms.auth.service.AuthService;
import org.confcms.cms.core.security.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final AuthService authService;

    @GetMapping("/new")
    public String showForm(Model model) {
        return "admin/users_new";
    }

    @PostMapping("/new")
    public String create(@RequestParam String email, @RequestParam String fullName,
                          @RequestParam Role role, Model model) {
        try {
            authService.createUserDirect(email, fullName, role);
            return "redirect:/admin/users/new?created=true";
        } catch (IllegalArgumentException iae) {
            model.addAttribute("error", iae.getMessage());
            return "admin/users_new";
        }
    }
}
