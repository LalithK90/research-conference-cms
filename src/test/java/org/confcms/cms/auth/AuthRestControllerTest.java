package org.confcms.cms.auth;

import org.confcms.cms.accesslog.AccessLogService;
import org.confcms.cms.service.EmailService;
import org.confcms.cms.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthRestControllerTest {

    @Mock
    private AuthService authService;
    @Mock
    private MagicLinkService magicLinkService;
    @Mock
    private EmailService emailService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AccessLogService accessLogService;
    @Mock
    private HttpServletRequest httpServletRequest;

    // A same-thread executor so the submitted no-op task actually runs during the test,
    // without pulling in Spring's real ThreadPoolTaskExecutor.
    private final Executor mailExecutor = Runnable::run;

    // 0ms so the test suite doesn't actually wait; the delay value itself is asserted via
    // unknownEmailDelayMillis below, not by measuring wall-clock time in the test.
    private final long unknownEmailDelayMillis = 0L;

    private static final String EXPECTED_RESPONSE = "If an account exists for this email, "
            + "we've sent a magic link. Please check your inbox, and your spam/junk folder, "
            + "for an email from us.";

    private AuthRestController controller;

    @BeforeEach
    void setUp() {
        when(httpServletRequest.getRemoteAddr()).thenReturn("203.0.113.5");
    }

    private AuthRestController newController(long delayMillis) {
        return new AuthRestController(authService, magicLinkService, emailService, userRepository,
                accessLogService, mailExecutor, delayMillis);
    }

    @Test
    void requestMagicLinkReturnsSameResponseWhenEmailIsUnknown() {
        controller = newController(unknownEmailDelayMillis);
        when(magicLinkService.createMagicLinkForEmail("unknown@example.com"))
                .thenThrow(new IllegalArgumentException("No user found for email"));

        ResponseEntity<?> response = controller.requestMagicLink("unknown@example.com", httpServletRequest);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(EXPECTED_RESPONSE);
    }

    @Test
    void requestMagicLinkNeverEmailsAnUnverifiedAddressWhenEmailIsUnknown() {
        // Unconditionally emailing the caller-supplied address on the unknown-email branch
        // would turn it into a spam/relay vector. The timing-side-channel fix must close the
        // timing gap without ever sending real mail to an address that was never confirmed
        // to belong to a registered user.
        controller = newController(unknownEmailDelayMillis);
        when(magicLinkService.createMagicLinkForEmail("unknown@example.com"))
                .thenThrow(new IllegalArgumentException("No user found for email"));

        controller.requestMagicLink("unknown@example.com", httpServletRequest);

        verify(emailService, never()).sendSimpleEmail(anyString(), anyString(), anyString());
    }

    @Test
    void requestMagicLinkReturnsSameResponseWhenEmailIsKnown() {
        controller = newController(unknownEmailDelayMillis);
        MagicLink link = new MagicLink();
        link.setToken("tok-123");
        link.setExpiresAt(java.time.LocalDateTime.now().plusHours(2));
        when(magicLinkService.createMagicLinkForEmail("known@example.com")).thenReturn(link);

        ResponseEntity<?> response = controller.requestMagicLink("known@example.com", httpServletRequest);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(EXPECTED_RESPONSE);
        verify(emailService).sendSimpleEmail(anyString(), anyString(), anyString());
    }

    @Test
    void requestMagicLinkPadsTheUnknownEmailBranchToApproximateTheDbInsertCost() {
        // The executor-submit fix alone didn't close the dominant gap: the known-email
        // branch does a real DB insert inside a transaction, the unknown-email branch does
        // only a failed SELECT. A configured floor delay on the unknown-email branch closes
        // that gap without needing to fabricate a row for a user that doesn't exist.
        long configuredDelay = 25L;
        controller = newController(configuredDelay);
        when(magicLinkService.createMagicLinkForEmail("unknown@example.com"))
                .thenThrow(new IllegalArgumentException("No user found for email"));

        long start = System.nanoTime();
        controller.requestMagicLink("unknown@example.com", httpServletRequest);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isGreaterThanOrEqualTo(configuredDelay);
    }

    @Test
    void requestMagicLinkAlwaysRecordsTheRequestRegardlessOfRateLimitOutcome() {
        controller = newController(unknownEmailDelayMillis);
        when(magicLinkService.createMagicLinkForEmail("someone@example.com"))
                .thenThrow(new IllegalArgumentException("No user found for email"));

        controller.requestMagicLink("someone@example.com", httpServletRequest);

        verify(accessLogService).logMagicLinkRequest(eq("someone@example.com"), eq(httpServletRequest));
    }

    @Test
    void requestMagicLinkStillCreatesALinkButDoesNotEmailWhenAlreadyRequestedWithinTheWindow() {
        // One request per (email, IP) per 30-minute window. createMagicLinkForEmail still
        // runs on the rate-limited branch (so a rate-limited known email pays the same cost
        // as a fresh one, not a third, cheaper, distinguishable timing bucket) -- only the
        // actual email send is skipped.
        controller = newController(unknownEmailDelayMillis);
        when(accessLogService.wasMagicLinkRequestedRecently(
                eq("known@example.com"), eq("203.0.113.5"), any(Duration.class)))
                .thenReturn(true);
        MagicLink link = new MagicLink();
        link.setToken("tok-123");
        link.setExpiresAt(java.time.LocalDateTime.now().plusHours(2));
        when(magicLinkService.createMagicLinkForEmail("known@example.com")).thenReturn(link);

        ResponseEntity<?> response = controller.requestMagicLink("known@example.com", httpServletRequest);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(EXPECTED_RESPONSE);
        verify(magicLinkService).createMagicLinkForEmail("known@example.com");
        verify(emailService, never()).sendSimpleEmail(anyString(), anyString(), anyString());
    }

    @Test
    void requestMagicLinkChecksTheThirtyMinuteWindowScopedToTheRequesterIp() {
        controller = newController(unknownEmailDelayMillis);
        when(magicLinkService.createMagicLinkForEmail("known@example.com")).thenReturn(new MagicLink());

        controller.requestMagicLink("known@example.com", httpServletRequest);

        verify(accessLogService).wasMagicLinkRequestedRecently(
                "known@example.com", "203.0.113.5", Duration.ofMinutes(30));
    }

    @Test
    void requestMagicLinkRateLimitDoesNotBlockTheSameEmailFromADifferentIp() {
        // A rate-limit keyed on email alone would let an attacker who knows a victim's email
        // lock the victim out of their own requests by submitting it first, from anywhere.
        // Scoping by (email, IP) means a request from the attacker's IP never affects whether
        // the victim's own IP is rate-limited.
        controller = newController(unknownEmailDelayMillis);
        when(accessLogService.wasMagicLinkRequestedRecently(
                eq("victim@example.com"), eq("203.0.113.5"), any(Duration.class)))
                .thenReturn(false);
        MagicLink link = new MagicLink();
        link.setToken("tok-456");
        link.setExpiresAt(java.time.LocalDateTime.now().plusHours(2));
        when(magicLinkService.createMagicLinkForEmail("victim@example.com")).thenReturn(link);

        controller.requestMagicLink("victim@example.com", httpServletRequest);

        verify(emailService).sendSimpleEmail(anyString(), anyString(), anyString());
    }
}
