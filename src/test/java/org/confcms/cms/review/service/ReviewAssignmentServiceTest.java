package org.confcms.cms.review.service;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.PersonInvitation;
import org.confcms.cms.domain.User;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.review.domain.AssignmentStatus;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.domain.ReviewDecline;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;
import org.confcms.cms.review.repository.ReviewBidRepository;
import org.confcms.cms.review.repository.ReviewDeclineRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.PersonInvitationService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

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
    @Mock
    private ReviewDeclineRepository reviewDeclineRepository;
    @Mock
    private PersonInvitationService personInvitationService;

    private ReviewAssignmentService service;
    private Conference conference;
    private Paper paper;

    @BeforeEach
    void setUp() {
        service = new ReviewAssignmentService(assignmentRepository, userRepository, paperRepository, bidRepository, committeeService, reviewDeclineRepository, personInvitationService);

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

    @Test
    void declineAssignmentRejectsBlankReason() {
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setId(50L);
        User reviewer = new User();
        reviewer.setId(20L);
        assignment.setReviewer(reviewer);
        assignment.setPaper(paper);

        when(assignmentRepository.findById(50L)).thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> service.declineAssignment(reviewer, 50L, "  ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void declineAssignmentRejectsWhenNoSuggestionProvided() {
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setId(50L);
        User reviewer = new User();
        reviewer.setId(20L);
        assignment.setReviewer(reviewer);
        assignment.setPaper(paper);

        when(assignmentRepository.findById(50L)).thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> service.declineAssignment(reviewer, 50L, "too busy", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void declineAssignmentRejectsWhenBothSuggestionFormsProvided() {
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setId(50L);
        User reviewer = new User();
        reviewer.setId(20L);
        assignment.setReviewer(reviewer);
        assignment.setPaper(paper);
        User existingSuggestion = new User();
        existingSuggestion.setId(60L);

        when(assignmentRepository.findById(50L)).thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> service.declineAssignment(reviewer, 50L, "too busy", 60L, "Jane Doe", "jane@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void declineAssignmentRejectsNonOwningReviewer() {
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setId(50L);
        User actualReviewer = new User();
        actualReviewer.setId(20L);
        assignment.setReviewer(actualReviewer);
        assignment.setPaper(paper);

        User stranger = new User();
        stranger.setId(99L);

        when(assignmentRepository.findById(50L)).thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> service.declineAssignment(stranger, 50L, "too busy", null, "Jane Doe", "jane@example.com"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void declineAssignmentWithExistingUserSuggestionCreatesNoInvitation() {
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setId(50L);
        User reviewer = new User();
        reviewer.setId(20L);
        assignment.setReviewer(reviewer);
        assignment.setPaper(paper);
        assignment.setStatus(AssignmentStatus.PENDING);

        User existingSuggestion = new User();
        existingSuggestion.setId(60L);

        when(assignmentRepository.findById(50L)).thenReturn(Optional.of(assignment));
        when(userRepository.findById(60L)).thenReturn(Optional.of(existingSuggestion));
        when(reviewDeclineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.declineAssignment(reviewer, 50L, "too busy", 60L, null, null);

        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.DECLINED);
        verifyNoInteractions(personInvitationService);
    }

    @Test
    void declineAssignmentWithExternalSuggestionCreatesReviewerSuggestionInvitation() {
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setId(50L);
        User reviewer = new User();
        reviewer.setId(20L);
        assignment.setReviewer(reviewer);
        assignment.setPaper(paper);
        assignment.setStatus(AssignmentStatus.PENDING);

        when(assignmentRepository.findById(50L)).thenReturn(Optional.of(assignment));
        when(reviewDeclineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.declineAssignment(reviewer, 50L, "too busy", null, "Jane Doe", "jane@example.com");

        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.DECLINED);
        verify(personInvitationService).createReviewerSuggestionInvitation(any(ReviewDecline.class), eq(conference), eq("Jane Doe"), eq("jane@example.com"));
    }
}
