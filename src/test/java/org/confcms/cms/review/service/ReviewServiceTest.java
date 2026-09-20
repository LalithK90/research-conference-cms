package org.confcms.cms.review.service;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.dto.PaperReviewView;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;
import org.confcms.cms.review.repository.ReviewRepository;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperAuthor;
import org.confcms.cms.submission.domain.PaperVersion;
import org.confcms.cms.submission.repository.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private ReviewAssignmentRepository assignmentRepository;
    @Mock
    private PaperRepository paperRepository;

    private ReviewService service;
    private Conference conference;
    private Paper paper;
    private ReviewAssignment assignment;
    private User reviewer;

    @BeforeEach
    void setUp() {
        service = new ReviewService(reviewRepository, assignmentRepository, paperRepository);

        conference = new Conference();
        conference.setId(1L);

        paper = new Paper();
        paper.setId(5L);
        paper.setTitle("A Paper");
        paper.setAbstractText("An abstract");
        paper.setTrack("Track A");
        paper.setConference(conference);

        PaperAuthor author = new PaperAuthor();
        author.setFullName("Jane Author");
        paper.getAuthors().add(author);

        PaperVersion version = new PaperVersion();
        version.setVersionNumber(1);
        version.setFilePath("/uploads/paper.pdf");
        version.setOriginalFilename("paper.pdf");
        paper.getVersions().add(version);

        reviewer = new User();
        reviewer.setId(20L);

        assignment = new ReviewAssignment();
        assignment.setId(100L);
        assignment.setPaper(paper);
        assignment.setReviewer(reviewer);
        assignment.setDueDate(LocalDateTime.of(2026, 10, 1, 0, 0));
    }

    @Test
    void getPaperForAssignmentRejectsNonOwningReviewer() {
        User stranger = new User();
        stranger.setId(99L);

        when(assignmentRepository.findById(100L)).thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> service.getPaperForAssignment(stranger, 100L))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void getPaperForAssignmentThrowsWhenAssignmentNotFound() {
        when(assignmentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPaperForAssignment(reviewer, 999L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getPaperForAssignmentReturnsAuthorNamesWhenNotBlind() {
        conference.setBlindReview(false);
        when(assignmentRepository.findById(100L)).thenReturn(Optional.of(assignment));

        Paper result = service.getPaperForAssignment(reviewer, 100L);

        assertThat(result).isEqualTo(paper);
    }

    @Test
    void getOwnedAssignmentRejectsNonOwningReviewer() {
        User stranger = new User();
        stranger.setId(99L);

        when(assignmentRepository.findById(100L)).thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> service.getOwnedAssignment(stranger, 100L))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void getOwnedAssignmentThrowsWhenAssignmentNotFound() {
        when(assignmentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOwnedAssignment(reviewer, 999L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getOwnedAssignmentReturnsAssignmentForOwningReviewer() {
        when(assignmentRepository.findById(100L)).thenReturn(Optional.of(assignment));

        ReviewAssignment result = service.getOwnedAssignment(reviewer, 100L);

        assertThat(result).isEqualTo(assignment);
        assertThat(result.getPaper()).isEqualTo(paper);
    }

    @Test
    void toReviewViewHidesAuthorNamesWhenBlind() {
        conference.setBlindReview(true);

        PaperReviewView view = service.toReviewView(paper, assignment);

        assertThat(view.authorNames()).isEmpty();
        assertThat(view.dueDate()).isEqualTo(assignment.getDueDate());
        assertThat(view.latestVersionNumber()).isEqualTo(1);
    }

    @Test
    void toReviewViewShowsAuthorNamesWhenNotBlind() {
        conference.setBlindReview(false);

        PaperReviewView view = service.toReviewView(paper, assignment);

        assertThat(view.authorNames()).containsExactly("Jane Author");
    }
}
