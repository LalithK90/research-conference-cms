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
    public String showForm() {
        return "admin/users_new";
    }

    @PostMapping("/new")
    public String create(@RequestParam String email, @RequestParam String fullName,
                          @RequestParam Role role, Model model) {
        // ADMIN is deliberately not creatable through this passwordless flow: the only
        // authentication path for a user with no password is the unthrottled magic-link
        // endpoint, so a passwordless ADMIN account would be reachable by anyone with
        // access to that inbox, with no record of who granted it. Promote an existing
        // account to ADMIN by other means instead.
        if (role == Role.ADMIN) {
            model.addAttribute("error", "Admin accounts cannot be created without a password. "
                    + "Create the user as Author or Reviewer, then promote them separately.");
            return "admin/users_new";
        }
        try {
            authService.createUserDirect(email, fullName, role);
            return "redirect:/admin/users/new?created=true";
        } catch (IllegalArgumentException iae) {
            model.addAttribute("error", iae.getMessage());
            return "admin/users_new";
        }
    }
}
