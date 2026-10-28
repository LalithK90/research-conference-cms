package org.confcms.cms.auth;

import org.confcms.cms.core.security.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

// Public self-service account sign-up -- the page /login was missing a link to. Always
// registers as AUTHOR, same restriction AuthRestController.register already enforces: letting
// a caller choose their own role here would let anyone self-promote to ADMIN. Separate from
// AuthRestController's own POST /auth/register (JSON, used by API callers): that endpoint
// returns a raw ResponseEntity<User>, not a view, so a plain HTML form posting there would
// dump JSON into the browser instead of redirecting.
@Controller
@RequestMapping("/auth/signup")
@RequiredArgsConstructor
public class SignupController {

    private final AuthService authService;

    @GetMapping
    public String form(Model model) {
        return "auth/signup";
    }

    @PostMapping
    public String submit(@RequestParam String email, @RequestParam String password,
                          @RequestParam String fullName, Model model) {
        try {
            authService.registerUser(email, password, fullName, Role.AUTHOR);
            return "redirect:/login?registered=true";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("email", email);
            model.addAttribute("fullName", fullName);
            return "auth/signup";
        }
    }
}
