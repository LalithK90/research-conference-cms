package org.confcms.cms.registration.web.controller;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.conference.Conference;
import org.confcms.cms.user.User;
import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.domain.Registration;
import org.confcms.cms.registration.service.RegistrationService;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.conference.ConferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationControllerTest {

    @Mock
    private ConferenceService conferenceService;
    @Mock
    private RegistrationService registrationService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserDetails userDetails;

    private RegistrationController controller;

    @BeforeEach
    void setUp() {
        controller = new RegistrationController(conferenceService, registrationService, userRepository);
    }

    private User user(long id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    @Test
    void processRegistrationRedirectsToLoginWhenNotAuthenticated() {
        String view = controller.processRegistration("REGULAR", null, null, new ExtendedModelMap());

        assertThat(view).isEqualTo("redirect:/login");
    }

    @Test
    void processRegistrationRedirectsToDashboardOnSuccess() {
        User u = user(1L, "author@example.com");
        Conference c = new Conference();
        when(userDetails.getUsername()).thenReturn("author@example.com");
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(u));
        when(conferenceService.getActiveConference()).thenReturn(c);
        when(registrationService.register(u, c, "REGULAR", null)).thenReturn(new Registration());

        String view = controller.processRegistration("REGULAR", null, userDetails, new ExtendedModelMap());

        assertThat(view).isEqualTo("redirect:/dashboard?registered=true");
    }

    @Test
    void processRegistrationRedisplaysFormWithErrorOnServiceRejection() {
        User u = user(1L, "author@example.com");
        Conference c = new Conference();
        when(userDetails.getUsername()).thenReturn("author@example.com");
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(u));
        when(conferenceService.getActiveConference()).thenReturn(c);
        when(registrationService.register(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("A bank slip file is required for bank transfer registrations"));

        Model model = new ExtendedModelMap();
        String view = controller.processRegistration("REGULAR", null, userDetails, model);

        assertThat(view).isEqualTo("public/register");
        assertThat(model.getAttribute("error")).isEqualTo("A bank slip file is required for bank transfer registrations");
    }

    @Test
    void reuploadSlipRejectsWhenNotTheOwningUser() {
        User owner = user(1L, "owner@example.com");
        User other = user(2L, "other@example.com");
        Registration reg = new Registration();
        reg.setUser(owner);
        reg.setPaymentStatus(PaymentStatus.FAILED);
        when(userDetails.getUsername()).thenReturn("other@example.com");
        when(userRepository.findByEmail("other@example.com")).thenReturn(Optional.of(other));
        when(registrationService.getRegistration(5L)).thenReturn(reg);
        MultipartFile slip = new MockMultipartFile("bankSlip", "x.pdf", "application/pdf", "%PDF-".getBytes());

        Model model = new ExtendedModelMap();
        assertThatCodeThrowsSecurityException(() -> controller.reuploadSlip(5L, slip, model, userDetails));
    }

    @Test
    void reuploadSlipRejectsWhenRegistrationIsNotFailed() {
        User owner = user(1L, "owner@example.com");
        Registration reg = new Registration();
        reg.setUser(owner);
        reg.setPaymentStatus(PaymentStatus.AWAITING_VERIFICATION);
        when(userDetails.getUsername()).thenReturn("owner@example.com");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(owner));
        when(registrationService.getRegistration(5L)).thenReturn(reg);
        MultipartFile slip = new MockMultipartFile("bankSlip", "x.pdf", "application/pdf", "%PDF-".getBytes());

        Model model = new ExtendedModelMap();
        try {
            controller.reuploadSlip(5L, slip, model, userDetails);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("not awaiting a re-upload");
        }
    }

    @Test
    void reuploadSlipSucceedsForTheOwningUserOnAFailedRegistration() {
        User owner = user(1L, "owner@example.com");
        Registration reg = new Registration();
        reg.setId(5L);
        reg.setUser(owner);
        reg.setPaymentStatus(PaymentStatus.FAILED);
        when(userDetails.getUsername()).thenReturn("owner@example.com");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(owner));
        when(registrationService.getRegistration(5L)).thenReturn(reg);
        MultipartFile slip = new MockMultipartFile("bankSlip", "x.pdf", "application/pdf", "%PDF-".getBytes());

        String view = controller.reuploadSlip(5L, slip, new ExtendedModelMap(), userDetails);

        assertThat(view).isEqualTo("redirect:/dashboard?slipResubmitted=true");
    }

    private interface ThrowingRunnable {
        void run();
    }

    private void assertThatCodeThrowsSecurityException(ThrowingRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("expected SecurityException");
        } catch (SecurityException expected) {
            assertThat(expected.getMessage()).contains("Not your registration");
        }
    }
}
