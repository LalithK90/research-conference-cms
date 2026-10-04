package org.confcms.cms.web.controller;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.user.User;
import org.confcms.cms.registration.PaymentStatus;
import org.confcms.cms.registration.Registration;
import org.confcms.cms.registration.RegistrationRepository;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.paper.Paper;
import org.confcms.cms.paper.SubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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

    @Test
    void uploadCameraReadyRedirectsWithSuccessMessage() {
        MultipartFile file = new MockMultipartFile("file", "camera-ready.pdf", "application/pdf", new byte[]{1, 2, 3});
        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.uploadCameraReady(10L, file, true, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/author/submissions");
        verify(submissionService).uploadCameraReady(eq(user), eq(10L), eq(file), eq(true));
        assertThat(redirectAttributes.getFlashAttributes().get("message")).isEqualTo("Camera-ready version submitted.");
        assertThat(redirectAttributes.getFlashAttributes()).doesNotContainKey("error");
    }

    @Test
    void uploadCameraReadyRedirectsWithErrorMessageOnFailure() {
        MultipartFile file = new MockMultipartFile("file", "camera-ready.pdf", "application/pdf", new byte[]{1, 2, 3});
        RedirectAttributes redirectAttributes = new RedirectAttributesModelMap();

        when(submissionService.uploadCameraReady(user, 10L, file, true))
                .thenThrow(new IllegalStateException("This paper is not currently awaiting a camera-ready submission"));

        String view = controller.uploadCameraReady(10L, file, true, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/author/submissions");
        assertThat(redirectAttributes.getFlashAttributes().get("error"))
                .isEqualTo("This paper is not currently awaiting a camera-ready submission");
        assertThat(redirectAttributes.getFlashAttributes()).doesNotContainKey("message");
    }
}
