package org.confcms.cms.decision;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.conference.CommitteeService;
import org.confcms.cms.conference.Conference;
import org.confcms.cms.conference.ConferenceRepository;
import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.review.ReviewAssignmentRepository;
import org.confcms.cms.review.ReviewAssignmentService;
import org.confcms.cms.review.ReviewRepository;
import org.confcms.cms.paper.Paper;
import org.confcms.cms.paper.PaperVersion;
import org.confcms.cms.paper.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDecisionViewControllerTest {

    @Mock
    private DecisionService decisionService;
    @Mock
    private PaperRepository paperRepository;
    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private CommitteeService committeeService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ConferenceRepository conferenceRepository;
    @Mock
    private ProceedingsService proceedingsService;
    @Mock
    private ReviewAssignmentService reviewAssignmentService;
    @Mock
    private ReviewAssignmentRepository reviewAssignmentRepository;

    private AdminDecisionViewController controller;
    private Conference conference;
    private Paper paper;

    @BeforeEach
    void setUp() {
        controller = new AdminDecisionViewController(decisionService, paperRepository, reviewRepository,
                committeeService, userRepository, conferenceRepository, proceedingsService,
                reviewAssignmentService, reviewAssignmentRepository);

        conference = new Conference();
        conference.setId(1L);

        paper = new Paper();
        paper.setId(5L);
        paper.setConference(conference);
    }

    private void authenticateAs(User user) {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, Collections.emptyList()));
    }

    @Test
    void paperDetailDeniesReviewerWithNoCommitteeRoleOnConference() {
        User stranger = new User();
        stranger.setId(30L);
        stranger.setEmail("stranger@example.com");
        stranger.setRole(Role.REVIEWER);
        authenticateAs(stranger);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(committeeService.isChairOrCoChair(stranger, conference)).thenReturn(false);

        Model model = new ExtendedModelMap();
        String view = controller.paperDetail(5L, model);

        assertThat(view).isEqualTo("admin/paper_detail");
        assertThat(model.getAttribute("error")).isEqualTo("Not authorized to view this paper");
        assertThat(model.getAttribute("paper")).isNull();
    }

    @Test
    void paperDetailAllowsAdminRegardlessOfCommitteeRole() {
        User admin = new User();
        admin.setId(40L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticateAs(admin);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(reviewRepository.findByPaperId(5L)).thenReturn(Collections.emptyList());

        Model model = new ExtendedModelMap();
        String view = controller.paperDetail(5L, model);

        assertThat(view).isEqualTo("admin/paper_detail");
        assertThat(model.getAttribute("paper")).isEqualTo(paper);
    }

    @Test
    void recordPlagiarismCheckRedirectsToTheOwningPaper() {
        User admin = new User();
        admin.setId(40L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticateAs(admin);

        PaperVersion version = new PaperVersion();
        version.setId(9L);
        version.setPaper(paper);
        when(decisionService.recordPlagiarismCheck(admin, 9L, 12.5, "Checked with Turnitin")).thenReturn(version);

        String view = controller.recordPlagiarismCheck(9L, 12.5, "Checked with Turnitin");

        assertThat(view).isEqualTo("redirect:/admin/decisions/ui/paper/5");
    }

    @Test
    void cameraReadyStatusShowsAcceptedAndCameraReadySubmittedPapersForAdmin() {
        User admin = new User();
        admin.setId(40L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticateAs(admin);

        Paper acceptedPaper = new Paper();
        acceptedPaper.setId(6L);
        acceptedPaper.setStatus(org.confcms.cms.paper.PaperStatus.ACCEPTED);
        acceptedPaper.setConference(conference);

        Paper cameraReadyPaper = new Paper();
        cameraReadyPaper.setId(7L);
        cameraReadyPaper.setStatus(org.confcms.cms.paper.PaperStatus.CAMERA_READY_SUBMITTED);
        cameraReadyPaper.setConference(conference);

        when(paperRepository.findByStatus(org.confcms.cms.paper.PaperStatus.ACCEPTED))
                .thenReturn(java.util.List.of(acceptedPaper));
        when(paperRepository.findByStatus(org.confcms.cms.paper.PaperStatus.CAMERA_READY_SUBMITTED))
                .thenReturn(java.util.List.of(cameraReadyPaper));

        Model model = new ExtendedModelMap();
        String view = controller.cameraReadyStatus(model);

        assertThat(view).isEqualTo("admin/camera_ready");
        @SuppressWarnings("unchecked")
        java.util.List<Paper> papers = (java.util.List<Paper>) model.getAttribute("papers");
        assertThat(papers).containsExactlyInAnyOrder(acceptedPaper, cameraReadyPaper);
    }

    @Test
    void cameraReadyStatusFiltersToOwnConferenceForNonAdminChair() {
        User chair = new User();
        chair.setId(41L);
        chair.setEmail("chair@example.com");
        chair.setRole(Role.REVIEWER);
        authenticateAs(chair);

        Conference otherConference = new Conference();
        otherConference.setId(99L);

        Paper ownPaper = new Paper();
        ownPaper.setId(6L);
        ownPaper.setStatus(org.confcms.cms.paper.PaperStatus.ACCEPTED);
        ownPaper.setConference(conference);

        Paper otherPaper = new Paper();
        otherPaper.setId(8L);
        otherPaper.setStatus(org.confcms.cms.paper.PaperStatus.ACCEPTED);
        otherPaper.setConference(otherConference);

        when(paperRepository.findByStatus(org.confcms.cms.paper.PaperStatus.ACCEPTED))
                .thenReturn(java.util.List.of(ownPaper, otherPaper));
        when(paperRepository.findByStatus(org.confcms.cms.paper.PaperStatus.CAMERA_READY_SUBMITTED))
                .thenReturn(java.util.List.of());
        when(committeeService.getChairOrCoChairConferenceIds(chair))
                .thenReturn(java.util.Set.of(conference.getId()));

        Model model = new ExtendedModelMap();
        controller.cameraReadyStatus(model);

        @SuppressWarnings("unchecked")
        java.util.List<Paper> papers = (java.util.List<Paper>) model.getAttribute("papers");
        assertThat(papers).containsExactly(ownPaper);
    }

    @Test
    void downloadProceedingsPdfAllowsAdminForAnyConference() throws Exception {
        User admin = new User();
        admin.setId(40L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticateAs(admin);

        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));

        ResponseEntity<?> response = controller.downloadProceedingsPdf(1L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(proceedingsService).generateProceedings(eq(conference), any());
    }

    @Test
    void downloadProceedingsPdfAllowsChairOfThatConference() throws Exception {
        User chair = new User();
        chair.setId(41L);
        chair.setEmail("chair@example.com");
        chair.setRole(Role.REVIEWER);
        authenticateAs(chair);

        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        when(committeeService.isChairOrCoChair(chair, conference)).thenReturn(true);

        ResponseEntity<?> response = controller.downloadProceedingsPdf(1L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    void downloadProceedingsPdfDeniesNonChairNonAdmin() throws Exception {
        User stranger = new User();
        stranger.setId(42L);
        stranger.setEmail("stranger@example.com");
        stranger.setRole(Role.REVIEWER);
        authenticateAs(stranger);

        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        when(committeeService.isChairOrCoChair(stranger, conference)).thenReturn(false);

        assertThatThrownBy(() -> controller.downloadProceedingsPdf(1L))
                .isInstanceOf(AccessDeniedException.class);

        verify(proceedingsService, never()).generateProceedings(any(), any());
    }

    @Test
    void downloadBibTeXAllowsAdminForAnyConference() throws Exception {
        User admin = new User();
        admin.setId(40L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticateAs(admin);

        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));

        ResponseEntity<?> response = controller.downloadBibTeX(1L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(proceedingsService).exportBibTeX(eq(conference), any());
    }

    @Test
    void downloadBibTeXDeniesNonChairNonAdmin() throws Exception {
        User stranger = new User();
        stranger.setId(42L);
        stranger.setEmail("stranger@example.com");
        stranger.setRole(Role.REVIEWER);
        authenticateAs(stranger);

        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        when(committeeService.isChairOrCoChair(stranger, conference)).thenReturn(false);

        assertThatThrownBy(() -> controller.downloadBibTeX(1L))
                .isInstanceOf(AccessDeniedException.class);

        verify(proceedingsService, never()).exportBibTeX(any(), any());
    }

    @Test
    void assignReviewerFormShowsPaperAssignmentsAndReviewers() {
        User admin = new User();
        admin.setId(40L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticateAs(admin);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(reviewAssignmentRepository.findByPaperId(5L)).thenReturn(java.util.List.of());
        when(userRepository.findByRole(Role.REVIEWER)).thenReturn(java.util.List.of());

        Model model = new ExtendedModelMap();
        String view = controller.assignReviewerForm(5L, model);

        assertThat(view).isEqualTo("admin/assign_reviewer");
        assertThat(model.getAttribute("paper")).isEqualTo(paper);
    }

    @Test
    void assignReviewerFormDeniesNonChairNonAdmin() {
        User stranger = new User();
        stranger.setId(42L);
        stranger.setEmail("stranger@example.com");
        stranger.setRole(Role.REVIEWER);
        authenticateAs(stranger);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(committeeService.isChairOrCoChair(stranger, conference)).thenReturn(false);

        Model model = new ExtendedModelMap();
        assertThatThrownBy(() -> controller.assignReviewerForm(5L, model))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assignReviewerManualAssignsAndRedirects() {
        User admin = new User();
        admin.setId(40L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticateAs(admin);

        User reviewer = new User();
        reviewer.setId(50L);
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(userRepository.findById(50L)).thenReturn(Optional.of(reviewer));

        org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes =
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();
        String view = controller.assignReviewerManual(5L, 50L, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/decisions/ui/paper/5/assign");
        verify(reviewAssignmentService).assignReviewer(admin, paper, reviewer);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("message");
    }

    @Test
    void assignReviewerManualCapturesSecurityExceptionAsFlashError() {
        User admin = new User();
        admin.setId(40L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticateAs(admin);

        User reviewer = new User();
        reviewer.setId(50L);
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(userRepository.findById(50L)).thenReturn(Optional.of(reviewer));
        org.mockito.Mockito.doThrow(new SecurityException("Not authorized"))
                .when(reviewAssignmentService).assignReviewer(admin, paper, reviewer);

        org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes =
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();
        String view = controller.assignReviewerManual(5L, 50L, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/decisions/ui/paper/5/assign");
        assertThat((java.util.Map<String, Object>) redirectAttributes.getFlashAttributes()).containsEntry("error", "Not authorized");
    }

    @Test
    void assignReviewerAutoAssignsAndRedirects() {
        User admin = new User();
        admin.setId(40L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticateAs(admin);

        org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes =
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();
        String view = controller.assignReviewerAuto(5L, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/decisions/ui/paper/5/assign");
        verify(reviewAssignmentService).autoAssignReviewers(admin, 5L);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("message");
    }

    @Test
    void assignReviewerAutoCapturesIllegalStateAsFlashError() {
        User admin = new User();
        admin.setId(40L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticateAs(admin);

        org.mockito.Mockito.doThrow(new IllegalStateException("Paper must be in SUBMITTED state"))
                .when(reviewAssignmentService).autoAssignReviewers(admin, 5L);

        org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes =
                new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();
        String view = controller.assignReviewerAuto(5L, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/decisions/ui/paper/5/assign");
        assertThat((java.util.Map<String, Object>) redirectAttributes.getFlashAttributes()).containsEntry("error", "Paper must be in SUBMITTED state");
    }
}
