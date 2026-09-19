package org.confcms.cms.review.service;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;
import org.confcms.cms.review.repository.ReviewBidRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.repository.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewAssignmentServiceTest {

    @Mock
    private ReviewAssignmentRepository assignmentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PaperRepository paperRepository;
    @Mock
    private ReviewBidRepository bidRepository;
    @Mock
    private CommitteeService committeeService;

    private ReviewAssignmentService service;
    private Conference conference;
    private Paper paper;

    @BeforeEach
    void setUp() {
        service = new ReviewAssignmentService(assignmentRepository, userRepository, paperRepository, bidRepository, committeeService);

        conference = new Conference();
        conference.setId(1L);

        paper = new Paper();
        paper.setId(5L);
        paper.setConference(conference);
        paper.setStatus(PaperStatus.SUBMITTED);
    }

    @Test
    void autoAssignReviewersRejectsNonChairNonAdmin() {
        User stranger = new User();
        stranger.setId(30L);
        stranger.setRole(Role.REVIEWER);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(committeeService.isChairOrCoChair(stranger, conference)).thenReturn(false);

        assertThatThrownBy(() -> service.autoAssignReviewers(stranger, 5L))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void assignReviewerRejectsNonChairNonAdmin() {
        User stranger = new User();
        stranger.setId(30L);
        stranger.setRole(Role.REVIEWER);

        User reviewer = new User();
        reviewer.setId(40L);

        when(committeeService.isChairOrCoChair(stranger, conference)).thenReturn(false);

        assertThatThrownBy(() -> service.assignReviewer(stranger, paper, reviewer))
                .isInstanceOf(SecurityException.class);
    }
}
