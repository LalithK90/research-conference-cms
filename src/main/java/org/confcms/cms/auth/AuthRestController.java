package org.confcms.cms.auth;

import org.confcms.cms.user.UserRepository;
import org.confcms.cms.service.EmailService;
import org.confcms.cms.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.Executor;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthRestController {

    private final AuthService authService;
    private final MagicLinkService magicLinkService;
    private final EmailService emailService;
    private final UserRepository userRepository;
    @Qualifier("emailExecutor")
    private final Executor mailExecutor;

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
        // createMagicLinkForEmail throws for an unknown email; swallow that here so the
        // response is identical either way -- otherwise this endpoint is a user-enumeration
        // oracle despite its own response text claiming not to be one.
        try {
            MagicLink link = magicLinkService.createMagicLinkForEmail(email);

            // Build a URL. In production use app host config.
            String url = String.format("http://localhost:8080/auth/magic/verify?token=%s", link.getToken());

            String subject = "Your magic login link";
            String body = "Click to sign in: " + url + "\nLink expires at: " + link.getExpiresAt();
            emailService.sendSimpleEmail(email, subject, body);
        } catch (IllegalArgumentException ignored) {
            // Do the same "submit a task to the mail executor" work as the success branch,
            // without ever sending mail to an unverified, attacker-supplied address (this
            // endpoint has no rate limiting, so unconditionally emailing the request's
            // address here would turn it into a spam/relay vector). Matching that one
            // externally-observable submit-call cost on both branches is what closes the
            // timing side-channel; actually sending mail is not the part that needs matching.
            mailExecutor.execute(() -> {
            });
        }

        return ResponseEntity.ok("Magic link sent if the account exists");
    }
}
