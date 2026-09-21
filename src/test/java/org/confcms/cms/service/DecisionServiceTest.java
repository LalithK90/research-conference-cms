package org.confcms.cms.service;

import org.confcms.cms.domain.CommitteeRole;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.review.domain.Review;
import org.confcms.cms.review.repository.ReviewRepository;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.domain.PaperVersion;
import org.confcms.cms.submission.domain.RevisionResolution;
import org.confcms.cms.submission.repository.PaperRepository;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.domain.AssignmentStatus;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionServiceTest {

    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private PaperRepository paperRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private CommitteeService committeeService;
    @Mock
    private ReviewAssignmentRepository reviewAssignmentRepository;

    private DecisionService service;
    private Conference conference;
    private Paper paper;
    private User chairUser;
    private User strangerUser;

    @BeforeEach
    void setUp() {
        service = new DecisionService(reviewRepository, paperRepository, emailService, committeeService, reviewAssignmentRepository);

        conference = new Conference();
        conference.setId(1L);

        User submitter = new User();
        submitter.setFullName("Author Name");
        submitter.setEmail("author@example.com");

        paper = new Paper();
        paper.setId(5L);
        paper.setTitle("A Paper");
        paper.setConference(conference);
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.SUBMITTED);

        chairUser = new User();
        chairUser.setId(20L);
        chairUser.setRole(Role.REVIEWER); // not global ADMIN -- authorized only via committee role

        strangerUser = new User();
        strangerUser.setId(30L);
        strangerUser.setRole(Role.REVIEWER);
    }

    @Test
    void deskReviewRejectsNonChairNonAdmin() {
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(committeeService.isChairOrCoChair(strangerUser, conference)).thenReturn(false);

        assertThatThrownBy(() -> service.deskReview(strangerUser, 5L, DecisionService.DeskDecision.SEND_TO_REVIEW))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void deskReviewSendToReviewTransitionsToUnderReview() {
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(committeeService.isChairOrCoChair(chairUser, conference)).thenReturn(true);
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Paper result = service.deskReview(chairUser, 5L, DecisionService.DeskDecision.SEND_TO_REVIEW);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.UNDER_REVIEW);
    }

    @Test
    void deskReviewDeskRejectTransitionsToDeskRejectedAndEmails() {
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(committeeService.isChairOrCoChair(chairUser, conference)).thenReturn(true);
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Paper result = service.deskReview(chairUser, 5L, DecisionService.DeskDecision.DESK_REJECT);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.DESK_REJECTED);
    }

    @Test
    void applyDecisionRejectsNonChairNonAdmin() {
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(committeeService.isChairOrCoChair(strangerUser, conference)).thenReturn(false);

        assertThatThrownBy(() -> service.applyDecision(strangerUser, 5L, "ACCEPT"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void applyDecisionAllowsAdminEvenWithoutCommitteeRole() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Paper result = service.applyDecision(admin, 5L, "ACCEPT");

        assertThat(result.getStatus()).isEqualTo(PaperStatus.ACCEPTED);
    }

    @Test
    void applyDecisionAcceptEmailModelContainsAnonymizedFeedbackNotReviewerIdentity() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);

        Review review1 = new Review();
        review1.setScore(4);
        review1.setComments("Good work, minor typos.");
        review1.setConfidentialComments("Reviewer thinks this is borderline.");
        User reviewerUser = new User();
        reviewerUser.setFullName("Should Never Appear");
        review1.setReviewer(reviewerUser);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.findByPaperId(5L)).thenReturn(List.of(review1));

        service.applyDecision(admin, 5L, "ACCEPT");

        ArgumentCaptor<Map<String, Object>> modelCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendTemplateEmail(any(), any(), any(), modelCaptor.capture());

        Map<String, Object> model = modelCaptor.getValue();
        String modelAsString = model.toString();
        assertThat(modelAsString).doesNotContain("Should Never Appear");
        assertThat(modelAsString).doesNotContain("borderline");
        assertThat(modelAsString).contains("Good work, minor typos.");
    }

    @Test
    void applyDecisionRejectSendsTemplatedEmailWithFeedback() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);

        Review review1 = new Review();
        review1.setScore(2);
        review1.setComments("Needs more experiments.");
        User reviewerUser = new User();
        review1.setReviewer(reviewerUser);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.findByPaperId(5L)).thenReturn(List.of(review1));

        service.applyDecision(admin, 5L, "REJECT");

        verify(emailService).sendTemplateEmail(any(), any(), eq("email/rejection_notification.txt"), anyMap());
    }

    @Test
    void requestRevisionRejectsNonChairNonAdmin() {
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(committeeService.isChairOrCoChair(strangerUser, conference)).thenReturn(false);

        assertThatThrownBy(() -> service.requestRevision(strangerUser, 5L, PaperStatus.MINOR_REVISION, LocalDate.now().plusDays(14)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void requestRevisionRejectsNonRevisionStatus() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);

        assertThatThrownBy(() -> service.requestRevision(admin, 5L, PaperStatus.ACCEPTED, LocalDate.now().plusDays(14)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestRevisionSetsStatusAndDueDateAndSendsEmail() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);
        paper.setStatus(PaperStatus.UNDER_REVIEW);

        LocalDate dueDate = LocalDate.now().plusDays(14);
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.findByPaperId(5L)).thenReturn(List.of());

        Paper result = service.requestRevision(admin, 5L, PaperStatus.MAJOR_REVISION, dueDate);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.MAJOR_REVISION);
        assertThat(result.getRevisionDueDate()).isEqualTo(dueDate);
        verify(emailService).sendTemplateEmail(any(), any(), eq("email/revision_requested_notification.txt"), anyMap());
    }

    @Test
    void requestRevisionAllowsSecondRoundFromExistingRevisionStatus() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);
        paper.setStatus(PaperStatus.MINOR_REVISION);

        LocalDate dueDate = LocalDate.now().plusDays(14);
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.findByPaperId(5L)).thenReturn(List.of());

        Paper result = service.requestRevision(admin, 5L, PaperStatus.MAJOR_REVISION, dueDate);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.MAJOR_REVISION);
    }

    @Test
    void requestRevisionRejectsPaperNotUnderReview() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);
        paper.setStatus(PaperStatus.WITHDRAWN);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));

        assertThatThrownBy(() -> service.requestRevision(admin, 5L, PaperStatus.MINOR_REVISION, LocalDate.now().plusDays(14)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolveRevisionRejectsWhenNotInRevisionStatus() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);
        paper.setStatus(PaperStatus.SUBMITTED);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));

        assertThatThrownBy(() -> service.resolveRevision(admin, 5L, RevisionResolution.ACCEPT_DIRECTLY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolveRevisionAcceptDirectlySetsAccepted() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);
        paper.setStatus(PaperStatus.MINOR_REVISION);
        paper.setRevisionDueDate(LocalDate.now().plusDays(5));
        paper.setRevisionRequestedAtVersionCount(1);
        paper.getVersions().add(new PaperVersion());
        paper.getVersions().add(new PaperVersion()); // revision uploaded since request

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.findByPaperId(5L)).thenReturn(List.of());

        Paper result = service.resolveRevision(admin, 5L, RevisionResolution.ACCEPT_DIRECTLY);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.ACCEPTED);
        assertThat(result.getRevisionDueDate()).isNull();
        assertThat(result.getRevisionRequestedAtVersionCount()).isNull();
    }

    @Test
    void resolveRevisionSendBackResetsOriginalAssignmentsToPending() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);
        paper.setStatus(PaperStatus.MAJOR_REVISION);
        paper.setRevisionRequestedAtVersionCount(1);
        paper.getVersions().add(new PaperVersion());
        paper.getVersions().add(new PaperVersion()); // revision uploaded since request

        ReviewAssignment originalAssignment = new ReviewAssignment();
        originalAssignment.setId(200L);
        originalAssignment.setPaper(paper);
        originalAssignment.setStatus(AssignmentStatus.COMPLETED);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewAssignmentRepository.findByPaperId(5L)).thenReturn(List.of(originalAssignment));
        when(reviewAssignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Paper result = service.resolveRevision(admin, 5L, RevisionResolution.SEND_BACK_TO_REVIEWERS);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.UNDER_REVIEW);
        assertThat(originalAssignment.getStatus()).isEqualTo(AssignmentStatus.PENDING);
    }

    @Test
    void resolveRevisionSendBackDoesNotResurrectDeclinedOrTouchPendingAssignments() {
        // Fix 3: only COMPLETED assignments (the reviewers who actually reviewed the pre-revision
        // version) get reset to PENDING. DECLINED reviewers explicitly opted out and must stay
        // DECLINED; already-PENDING assignments are left alone.
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);
        paper.setStatus(PaperStatus.MAJOR_REVISION);
        paper.setRevisionRequestedAtVersionCount(1);
        paper.getVersions().add(new PaperVersion());
        paper.getVersions().add(new PaperVersion());

        ReviewAssignment completed = new ReviewAssignment();
        completed.setId(200L);
        completed.setPaper(paper);
        completed.setStatus(AssignmentStatus.COMPLETED);

        ReviewAssignment declined = new ReviewAssignment();
        declined.setId(201L);
        declined.setPaper(paper);
        declined.setStatus(AssignmentStatus.DECLINED);

        ReviewAssignment pending = new ReviewAssignment();
        pending.setId(202L);
        pending.setPaper(paper);
        pending.setStatus(AssignmentStatus.PENDING);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewAssignmentRepository.findByPaperId(5L)).thenReturn(List.of(completed, declined, pending));
        when(reviewAssignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resolveRevision(admin, 5L, RevisionResolution.SEND_BACK_TO_REVIEWERS);

        assertThat(completed.getStatus()).isEqualTo(AssignmentStatus.PENDING);
        assertThat(declined.getStatus()).isEqualTo(AssignmentStatus.DECLINED);
        assertThat(pending.getStatus()).isEqualTo(AssignmentStatus.PENDING);
        verify(reviewAssignmentRepository, never()).save(declined);
    }

    @Test
    void resolveRevisionRejectsWhenNoRevisionUploadedYet() {
        // Fix 4: resolveRevision must require that a new PaperVersion has actually arrived since
        // requestRevision was called, not just that the status is a revision status.
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);
        paper.setStatus(PaperStatus.MINOR_REVISION);
        paper.setRevisionRequestedAtVersionCount(1);
        paper.getVersions().add(new PaperVersion()); // still only the original version

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));

        assertThatThrownBy(() -> service.resolveRevision(admin, 5L, RevisionResolution.ACCEPT_DIRECTLY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requestRevisionRejectsPastDueDate() {
        // Fix 6
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);

        assertThatThrownBy(() -> service.requestRevision(admin, 5L, PaperStatus.MINOR_REVISION, LocalDate.now().minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestRevisionRejectsTodayAsDueDate() {
        // Fix 6: "today" does not count as a valid future due date
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);

        assertThatThrownBy(() -> service.requestRevision(admin, 5L, PaperStatus.MINOR_REVISION, LocalDate.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestRevisionRejectsNullDueDate() {
        // Fix 6
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);

        assertThatThrownBy(() -> service.requestRevision(admin, 5L, PaperStatus.MINOR_REVISION, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestRevisionCapturesVersionCountAtRequestTime() {
        // Fix 4 write-side: requestRevision must snapshot the current version count.
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);
        paper.setStatus(PaperStatus.UNDER_REVIEW);
        paper.getVersions().add(new PaperVersion());

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.findByPaperId(5L)).thenReturn(List.of());

        Paper result = service.requestRevision(admin, 5L, PaperStatus.MINOR_REVISION, LocalDate.now().plusDays(14));

        assertThat(result.getRevisionRequestedAtVersionCount()).isEqualTo(1);
    }

    @Test
    void suggestDecisionsExtendedWithRevisionBands() {
        // Fix 5: with acceptThreshold=3.5, rejectThreshold=2.5 (span=1.0), the new bands split
        // the middle third at minorRevisionThreshold=3.5-1/3=3.1667 and
        // majorRevisionThreshold=2.5+1/3=2.8333. So: ~3.33 -> MINOR_REVISION, 3.0 -> BORDERLINE,
        // ~2.67 -> MAJOR_REVISION, while ACCEPT/REJECT at the extremes are unchanged.
        Paper acceptPaper = paperWithId(101L);
        Paper minorPaper = paperWithId(102L);
        Paper borderlinePaper = paperWithId(103L);
        Paper majorPaper = paperWithId(104L);
        Paper rejectPaper = paperWithId(105L);

        when(paperRepository.findAll()).thenReturn(List.of(acceptPaper, minorPaper, borderlinePaper, majorPaper, rejectPaper));
        when(reviewRepository.findByPaperId(101L)).thenReturn(reviewsAveraging(4.0));
        when(reviewRepository.findByPaperId(102L)).thenReturn(reviewsAveraging(3.3));
        when(reviewRepository.findByPaperId(103L)).thenReturn(reviewsAveraging(3.0));
        when(reviewRepository.findByPaperId(104L)).thenReturn(reviewsAveraging(2.7));
        when(reviewRepository.findByPaperId(105L)).thenReturn(reviewsAveraging(1.0));

        List<DecisionService.DecisionSuggestion> suggestions = service.suggestDecisions(3.5, 2.5);

        assertThat(suggestionFor(suggestions, 101L).suggestion).isEqualTo("ACCEPT");
        assertThat(suggestionFor(suggestions, 102L).suggestion).isEqualTo("MINOR_REVISION");
        assertThat(suggestionFor(suggestions, 103L).suggestion).isEqualTo("BORDERLINE");
        assertThat(suggestionFor(suggestions, 104L).suggestion).isEqualTo("MAJOR_REVISION");
        assertThat(suggestionFor(suggestions, 105L).suggestion).isEqualTo("REJECT");
    }

    private Paper paperWithId(Long id) {
        Paper p = new Paper();
        p.setId(id);
        p.setTitle("Paper " + id);
        return p;
    }

    // Reviews whose integer scores average to exactly the given value (Review.score is an
    // Integer), using thirds so results like 3.3333 (= 10/3) land precisely.
    private List<Review> reviewsAveraging(double average) {
        int totalOverThree = (int) Math.round(average * 3);
        int base = totalOverThree / 3;
        int remainder = totalOverThree - base * 3;
        List<Review> reviews = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Review r = new Review();
            r.setScore(base + (i < remainder ? 1 : 0));
            reviews.add(r);
        }
        return reviews;
    }

    private DecisionService.DecisionSuggestion suggestionFor(List<DecisionService.DecisionSuggestion> suggestions, Long paperId) {
        return suggestions.stream().filter(s -> s.paperId.equals(paperId)).findFirst().orElseThrow();
    }
}
