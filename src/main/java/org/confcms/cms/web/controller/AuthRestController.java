package org.confcms.cms.web.controller;

import org.confcms.cms.auth.service.AuthService;
import org.confcms.cms.auth.service.MagicLinkService;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.domain.MagicLink;
import org.confcms.cms.service.EmailService;
import org.confcms.cms.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthRestController {

    private final AuthService authService;
    private final MagicLinkService magicLinkService;
    private final EmailService emailService;
    private final UserRepository userRepository;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestParam String email,
                                      @RequestParam String password,
                                      @RequestParam String fullName) {
        // Public self-registration must never accept a caller-supplied role: this endpoint is
        // permitAll() in SecurityConfig, so honoring a "role" parameter here would let anyone
        // register themselves as ADMIN. Elevated roles are granted separately by an admin.
        User user = authService.registerUser(email, password, fullName, org.confcms.cms.core.security.Role.AUTHOR);
        return ResponseEntity.ok(user);
    }

    @PostMapping("/magic/request")
    public ResponseEntity<?> requestMagicLink(@RequestParam String email) {
        // Create magic link (throws if user not found)
        MagicLink link = magicLinkService.createMagicLinkForEmail(email);

        // Build a URL. In production use app host config.
        String url = String.format("http://localhost:8080/auth/magic/verify?token=%s", link.getToken());

        // Send email (best-effort)
        String subject = "Your magic login link";
        String body = "Click to sign in: " + url + "\nLink expires at: " + link.getExpiresAt();
        emailService.sendSimpleEmail(email, subject, body);

        return ResponseEntity.ok("Magic link sent if the account exists");
    }
}
