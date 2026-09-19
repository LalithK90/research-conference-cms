package org.confcms.cms.web.controller;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.review.repository.ReviewRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.DecisionService;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.repository.PaperRepository;
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
}
