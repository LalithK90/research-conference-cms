package org.confcms.cms.web.controller;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.conference.Conference;
import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.review.ReviewRepository;
import org.confcms.cms.conference.CommitteeService;
import org.confcms.cms.service.DecisionService;
import org.confcms.cms.paper.Paper;
import org.confcms.cms.paper.PaperVersion;
import org.confcms.cms.paper.PaperRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    private AdminDecisionViewController controller;
    private Conference conference;
    private Paper paper;

    @BeforeEach
    void setUp() {
        controller = new AdminDecisionViewController(decisionService, paperRepository, reviewRepository, committeeService, userRepository);

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
        when(committeeService.isChairOrCoChair(chair, conference)).thenReturn(true);
        when(committeeService.isChairOrCoChair(chair, otherConference)).thenReturn(false);

        Model model = new ExtendedModelMap();
        controller.cameraReadyStatus(model);

        @SuppressWarnings("unchecked")
        java.util.List<Paper> papers = (java.util.List<Paper>) model.getAttribute("papers");
        assertThat(papers).containsExactly(ownPaper);
    }
}
