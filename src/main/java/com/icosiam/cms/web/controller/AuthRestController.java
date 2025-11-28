package com.icosiam.cms.web.controller;

import com.icosiam.cms.auth.service.AuthService;
import com.icosiam.cms.auth.service.MagicLinkService;
import com.icosiam.cms.repository.UserRepository;
import com.icosiam.cms.domain.MagicLink;
import com.icosiam.cms.service.EmailService;
import com.icosiam.cms.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

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
                                      @RequestParam String fullName,
                                      @RequestParam(required = false, defaultValue = "AUTHOR") String role) {
        User user = authService.registerUser(email, password, fullName, com.icosiam.cms.domain.Role.valueOf(role));
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

    @GetMapping("/magic/verify")
    public ResponseEntity<?> verifyMagicLink(@RequestParam String token) {
        MagicLink link = magicLinkService.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid magic link"));

        if (link.isUsed()) {
            return ResponseEntity.badRequest().body("This magic link has already been used");
        }

        if (link.getExpiresAt().isBefore(LocalDateTime.now())) {
            return ResponseEntity.badRequest().body("Magic link expired");
        }

        // Mark used
        magicLinkService.markUsed(link);

        // Authenticate the user by setting SecurityContext (session-based auth)
        User user = link.getUser();
        List<SimpleGrantedAuthority> auths = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(user.getEmail(), null, auths);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        return ResponseEntity.ok("Authenticated");
    }
}
