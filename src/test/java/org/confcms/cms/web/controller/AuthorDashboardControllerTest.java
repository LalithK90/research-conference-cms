package org.confcms.cms.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.domain.Registration;
import org.confcms.cms.registration.repository.RegistrationRepository;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.service.SubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorDashboardControllerTest {

    @Mock
    private SubmissionService submissionService;
    @Mock
    private RegistrationRepository registrationRepository;
    @Mock
    private UserRepository userRepository;

    private AuthorDashboardController controller;
    private User user;
    private Conference conference;

    @BeforeEach
    void setUp() {
        controller = new AuthorDashboardController(submissionService, registrationRepository, userRepository);

        user = new User();
        user.setId(1L);
        user.setEmail("author@example.com");

        conference = new Conference();
        conference.setId(2L);
        conference.setTitle("ICSE 2027");

        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("author@example.com", null, Collections.emptyList()));
    }

    @Test
    void mySubmissionsListsPapersAndRegistrationForActingUser() {
        Paper paper = new Paper();
        paper.setId(10L);
        paper.setTitle("A Great Paper");
        paper.setConference(conference);
        when(submissionService.getPapersBySubmitter(user)).thenReturn(List.of(paper));

        Registration registration = new Registration();
        registration.setConference(conference);
        registration.setPaymentStatus(PaymentStatus.AWAITING_VERIFICATION);
        when(registrationRepository.findByUserId(1L)).thenReturn(List.of(registration));

        Model model = new ExtendedModelMap();
        String view = controller.mySubmissions(model);

        assertThat(view).isEqualTo("author/submissions");
        assertThat(model.getAttribute("papers")).isEqualTo(List.of(paper));

        @SuppressWarnings("unchecked")
        Map<Long, Registration> registrationsByConference =
                (Map<Long, Registration>) model.getAttribute("registrationsByConference");
        assertThat(registrationsByConference).containsEntry(2L, registration);
    }

    @Test
    void mySubmissionsHandlesNoRegistrations() {
        when(submissionService.getPapersBySubmitter(user)).thenReturn(Collections.emptyList());
        when(registrationRepository.findByUserId(1L)).thenReturn(Collections.emptyList());

        Model model = new ExtendedModelMap();
        String view = controller.mySubmissions(model);

        assertThat(view).isEqualTo("author/submissions");
        assertThat(model.getAttribute("papers")).isEqualTo(Collections.emptyList());
        assertThat((Map<?, ?>) model.getAttribute("registrationsByConference")).isEmpty();
    }
}
