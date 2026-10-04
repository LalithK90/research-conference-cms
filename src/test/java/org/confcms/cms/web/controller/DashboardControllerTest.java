package org.confcms.cms.web.controller;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.conference.Conference;
import org.confcms.cms.user.User;
import org.confcms.cms.registration.PaymentStatus;
import org.confcms.cms.registration.Registration;
import org.confcms.cms.registration.RegistrationRepository;
import org.confcms.cms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RegistrationRepository registrationRepository;

    private DashboardController controller;

    @BeforeEach
    void setUp() {
        controller = new DashboardController(userRepository, registrationRepository);
        // Not every test cares about registrations; stub leniently so tests that
        // don't touch it aren't penalized by Mockito's strict-stubs unused-stub check.
        lenient().when(registrationRepository.findByUserId(any())).thenReturn(List.of());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, Collections.emptyList()));
    }

    @Test
    void adminIsRedirectedToAdminDashboard() {
        User admin = new User();
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticateAs(admin);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));

        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        String view = controller.dashboard(request, model);

        assertThat(view).isEqualTo("redirect:/admin/dashboard");
    }

    @Test
    void reviewerSeesTheMinimalDashboardWithNoPendingPrompt() {
        User reviewer = new User();
        reviewer.setId(2L);
        reviewer.setEmail("reviewer@example.com");
        reviewer.setRole(Role.REVIEWER);
        authenticateAs(reviewer);
        when(userRepository.findByEmail("reviewer@example.com")).thenReturn(Optional.of(reviewer));

        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        String view = controller.dashboard(request, model);

        assertThat(view).isEqualTo("dashboard");
        assertThat(model.getAttribute("user")).isEqualTo(reviewer);
        assertThat(model.getAttribute("passwordPromptPending")).isEqualTo(false);
        assertThat(model.getAttribute("rejectedRegistration")).isNull();
    }

    @Test
    void authorSeesPasswordPromptWhenSessionFlagIsSet() {
        User author = new User();
        author.setId(3L);
        author.setEmail("author@example.com");
        author.setRole(Role.AUTHOR);
        authenticateAs(author);
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(author));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("passwordPromptPending", true);
        Model model = new ExtendedModelMap();

        String view = controller.dashboard(request, model);

        assertThat(view).isEqualTo("dashboard");
        assertThat(model.getAttribute("passwordPromptPending")).isEqualTo(true);
    }

    @Test
    void authorSeesRejectedRegistrationWhenOneExists() {
        User author = new User();
        author.setId(4L);
        author.setEmail("author2@example.com");
        author.setRole(Role.AUTHOR);
        authenticateAs(author);
        when(userRepository.findByEmail("author2@example.com")).thenReturn(Optional.of(author));

        Conference conference = new Conference();
        conference.setId(20L);
        Registration rejected = new Registration();
        rejected.setConference(conference);
        rejected.setPaymentStatus(PaymentStatus.FAILED);
        rejected.setRejectionReason("Illegible scan");
        when(registrationRepository.findByUserId(4L)).thenReturn(List.of(rejected));
        when(registrationRepository.findByUserIdAndConferenceIdAndPaymentStatusNot(4L, 20L, PaymentStatus.FAILED))
                .thenReturn(Optional.empty());

        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        controller.dashboard(request, model);

        assertThat(model.getAttribute("rejectedRegistration")).isEqualTo(rejected);
    }

    @Test
    void authorDoesNotSeeAFailedRegistrationWithNoRejectionReasonAsRejected() {
        User author = new User();
        author.setId(5L);
        author.setEmail("author3@example.com");
        author.setRole(Role.AUTHOR);
        authenticateAs(author);
        when(userRepository.findByEmail("author3@example.com")).thenReturn(Optional.of(author));

        Registration failedNoReason = new Registration();
        failedNoReason.setPaymentStatus(PaymentStatus.FAILED);
        failedNoReason.setRejectionReason(null);
        when(registrationRepository.findByUserId(5L)).thenReturn(List.of(failedNoReason));

        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        controller.dashboard(request, model);

        assertThat(model.getAttribute("rejectedRegistration")).isNull();
    }

    @Test
    void authorDoesNotSeeAStaleRejectionBannerAfterRegisteringAgainForTheSameConference() {
        // Reproduces the H2 fix: a rejected registration whose registrant has since
        // registered again (successfully or otherwise) for the same conference should not
        // keep showing the old rejection banner / re-upload link.
        User author = new User();
        author.setId(6L);
        author.setEmail("author4@example.com");
        author.setRole(Role.AUTHOR);
        authenticateAs(author);
        when(userRepository.findByEmail("author4@example.com")).thenReturn(Optional.of(author));

        Conference conference = new Conference();
        conference.setId(30L);
        Registration staleRejected = new Registration();
        staleRejected.setConference(conference);
        staleRejected.setPaymentStatus(PaymentStatus.FAILED);
        staleRejected.setRejectionReason("Illegible scan");
        when(registrationRepository.findByUserId(6L)).thenReturn(List.of(staleRejected));
        when(registrationRepository.findByUserIdAndConferenceIdAndPaymentStatusNot(6L, 30L, PaymentStatus.FAILED))
                .thenReturn(Optional.of(new Registration()));

        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        controller.dashboard(request, model);

        assertThat(model.getAttribute("rejectedRegistration")).isNull();
    }
}
