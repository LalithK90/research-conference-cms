package org.confcms.cms.auth;

import org.confcms.cms.accesslog.AccessLogService;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.service.EmailService;
import org.confcms.cms.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/auth")
public class AuthRestController {

    private static final Duration MAGIC_LINK_REQUEST_WINDOW = Duration.ofMinutes(30);

    private final AuthService authService;
    private final MagicLinkService magicLinkService;
    private final EmailService emailService;
    private final UserRepository userRepository;
    private final AccessLogService accessLogService;
    private final Executor mailExecutor;
    // Floor delay applied only when the email is unknown, approximating the DB insert +
    // transaction-commit cost that the known-email branch pays and the unknown-email branch
    // otherwise wouldn't -- that insert is the dominant remaining timing signal between the
    // two branches, bigger and more variable than the executor-submit cost alone closes.
    // Calibrate against this deployment's real p50/p75 MagicLink insert latency; there is no
    // universally correct value since it depends entirely on the production DB's own latency.
    private final long unknownEmailDelayMillis;

    // Explicit constructor (not @RequiredArgsConstructor): Lombok's generated constructor
    // does not carry field-level @Qualifier/@Value onto the constructor's own parameters, so
    // Spring can't resolve the Executor qualifier or the primitive long property through it.
    public AuthRestController(AuthService authService,
                               MagicLinkService magicLinkService,
                               EmailService emailService,
                               UserRepository userRepository,
                               AccessLogService accessLogService,
                               @Qualifier("emailExecutor") Executor mailExecutor,
                               @Value("${app.auth.magic-link.unknown-email-delay-millis:30}") long unknownEmailDelayMillis) {
        this.authService = authService;
        this.magicLinkService = magicLinkService;
        this.emailService = emailService;
        this.userRepository = userRepository;
        this.accessLogService = accessLogService;
        this.mailExecutor = mailExecutor;
        this.unknownEmailDelayMillis = unknownEmailDelayMillis;
    }

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
    public ResponseEntity<?> requestMagicLink(@RequestParam String email, HttpServletRequest request) {
        // Checked (and the outcome applied) identically whether or not the email is known --
        // rate-limiting only known emails would itself be a user-enumeration oracle, and the
        // response text must stay identical either way for the same reason as the branches
        // below. One request per email per 30-minute window; a repeat request within the
        // window returns the same success message without sending a new email.
        boolean alreadyRequestedRecently =
                accessLogService.wasMagicLinkRequestedRecently(email, MAGIC_LINK_REQUEST_WINDOW);
        accessLogService.logMagicLinkRequest(email, request);

        if (alreadyRequestedRecently) {
            // Rate-limited: pay the same "cheap branch" cost as an unknown email (no DB
            // insert, no real email) so this doesn't become a third, distinguishable timing
            // bucket alongside known/unknown.
            mailExecutor.execute(() -> {
            });
            padForMissingDatabaseInsert();
        } else {
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
                // endpoint has no rate limiting beyond the per-email window above, so
                // unconditionally emailing the request's address here would turn it into a
                // spam/relay vector). Matching that one externally-observable submit-call cost
                // on both branches is what closes the timing side-channel; actually sending
                // mail is not the part that needs matching.
                mailExecutor.execute(() -> {
                });
                padForMissingDatabaseInsert();
            }
        }

        return ResponseEntity.ok("If an account exists for this email, we've sent a magic "
                + "link. Please check your inbox, and your spam/junk folder, for an email "
                + "from us.");
    }

    private void padForMissingDatabaseInsert() {
        if (unknownEmailDelayMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(unknownEmailDelayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
