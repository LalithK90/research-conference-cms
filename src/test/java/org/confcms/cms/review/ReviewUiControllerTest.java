package org.confcms.cms.review;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.conference.ConferenceService;
import org.confcms.cms.conflictdeclaration.ConflictDeclaration;
import org.confcms.cms.conflictdeclaration.ConflictDeclarationRepository;
import org.confcms.cms.conflictdeclaration.ConflictDeclarationService;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.paper.Paper;
import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewUiControllerTest {

    @Mock
    private ReviewAssignmentRepository assignmentRepository;
    @Mock
    private ReviewService reviewService;
    @Mock
    private ReviewAssignmentService reviewAssignmentService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ConferenceService conferenceService;
    @Mock
    private ConflictDeclarationService conflictDeclarationService;
    @Mock
    private ConflictDeclarationRepository conflictDeclarationRepository;

    private ReviewUiController controller;
    private User reviewer;

    @BeforeEach
    void setUp() {
        controller = new ReviewUiController(assignmentRepository, reviewService, reviewAssignmentService, userRepository,
                conferenceService, conflictDeclarationService, conflictDeclarationRepository);

        reviewer = new User();
        reviewer.setId(1L);
        reviewer.setEmail("reviewer@example.com");
        reviewer.setRole(Role.REVIEWER);
        when(userRepository.findByEmail("reviewer@example.com")).thenReturn(Optional.of(reviewer));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("reviewer@example.com", null, Collections.emptyList()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private ReviewAssignment assignment(Long id, AssignmentStatus status) {
        ReviewAssignment a = new ReviewAssignment();
        a.setId(id);
        a.setReviewer(reviewer);
        a.setStatus(status);
        a.setAssignedAt(LocalDateTime.now());

        Paper paper = new Paper();
        paper.setId(10L);
        paper.setTitle("Test Paper");
        Conference conference = new Conference();
        conference.setId(20L);
        conference.setBlindReview(false);
        paper.setConference(conference);
        a.setPaper(paper);
        return a;
    }

    @Test
    void myAssignmentsShowsOnlyThisReviewersAssignments() {
        ReviewAssignment a = assignment(1L, AssignmentStatus.PENDING);
        when(assignmentRepository.findByReviewerId(1L)).thenReturn(List.of(a));

        Model model = new ExtendedModelMap();
        String view = controller.myAssignments(model);

        assertThat(view).isEqualTo("review/my_assignments");
        assertThat(model.getAttribute("assignments")).isEqualTo(List.of(a));
    }

    @Test
    void assignmentDetailShowsPaperViewAndOtherReviewers() {
        ReviewAssignment a = assignment(1L, AssignmentStatus.PENDING);
        PaperReviewView view = new PaperReviewView(10L, "Test Paper", "Abstract", "AI", 1, null, List.of("Author"));
        when(reviewService.getOwnedAssignment(reviewer, 1L)).thenReturn(a);
        when(reviewService.toReviewView(a.getPaper(), a)).thenReturn(view);

        User otherReviewer = new User();
        otherReviewer.setId(2L);
        when(userRepository.findByRole(Role.REVIEWER)).thenReturn(List.of(reviewer, otherReviewer));

        Model model = new ExtendedModelMap();
        String viewName = controller.assignmentDetail(1L, model);

        assertThat(viewName).isEqualTo("review/assignment_detail");
        assertThat(model.getAttribute("paper")).isEqualTo(view);
        @SuppressWarnings("unchecked")
        List<User> reviewers = (List<User>) model.getAttribute("reviewers");
        assertThat(reviewers).containsExactly(otherReviewer);
    }

    @Test
    void assignmentDetailDeniesAccessToAnotherReviewersAssignment() {
        when(reviewService.getOwnedAssignment(reviewer, 1L))
                .thenThrow(new SecurityException("Not authorized to view this paper"));

        Model model = new ExtendedModelMap();
        assertThatThrownBy(() -> controller.assignmentDetail(1L, model))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assignmentDetailShowsErrorForMissingAssignment() {
        when(reviewService.getOwnedAssignment(reviewer, 1L))
                .thenThrow(new IllegalArgumentException("Assignment not found"));

        Model model = new ExtendedModelMap();
        String view = controller.assignmentDetail(1L, model);

        assertThat(view).isEqualTo("review/assignment_detail");
        assertThat(model.getAttribute("error")).isEqualTo("Assignment not found");
    }

    @Test
    void submitReviewRedirectsToMyAssignmentsOnSuccess() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.submitReview(1L, 4, "Good work", "Confidential note", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/review/ui");
        verify(reviewService).submitReview(reviewer, 1L, 4, "Good work", "Confidential note");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("message");
    }

    @Test
    void submitReviewRedirectsBackToAssignmentOnFailure() {
        Mockito.doThrow(new IllegalStateException("Review already submitted"))
                .when(reviewService).submitReview(reviewer, 1L, 4, null, null);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.submitReview(1L, 4, null, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/review/ui/assignment/1");
        assertThat((Map<String, Object>) redirectAttributes.getFlashAttributes())
                .containsEntry("error", "Review already submitted");
    }

    @Test
    void declineAssignmentRedirectsToMyAssignmentsOnSuccess() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.declineAssignment(1L, "Too busy", null, "Someone", "someone@example.com", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/review/ui");
        verify(reviewAssignmentService).declineAssignment(reviewer, 1L, "Too busy", null, "Someone", "someone@example.com");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("message");
    }

    @Test
    void declineAssignmentRedirectsBackToAssignmentOnFailure() {
        Mockito.doThrow(new IllegalArgumentException("A reason is required to decline an assignment"))
                .when(reviewAssignmentService).declineAssignment(reviewer, 1L, "", null, null, null);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.declineAssignment(1L, "", null, null, null, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/review/ui/assignment/1");
        assertThat((Map<String, Object>) redirectAttributes.getFlashAttributes())
                .containsEntry("error", "A reason is required to decline an assignment");
    }

    @Test
    void conflictsShowsDeclarableAuthorsExcludingSelfAndAlreadyDeclaredIdsForNonBlindConference() {
        Conference conference = new Conference();
        conference.setId(5L);
        conference.setBlindReview(false);
        when(conferenceService.getActiveConference()).thenReturn(conference);

        User self = reviewer;
        User authorOne = new User();
        authorOne.setId(100L);
        User authorTwo = new User();
        authorTwo.setId(101L);
        when(conflictDeclarationService.declarableAuthors(conference)).thenReturn(List.of(self, authorOne, authorTwo));

        ConflictDeclaration existing = new ConflictDeclaration();
        existing.setDeclaredAgainstUser(authorOne);
        when(conflictDeclarationRepository.findByConferenceIdAndReviewerId(5L, 1L)).thenReturn(List.of(existing));

        Model model = new ExtendedModelMap();
        String view = controller.conflicts(model);

        assertThat(view).isEqualTo("review/conflicts");
        @SuppressWarnings("unchecked")
        List<User> authors = (List<User>) model.getAttribute("authors");
        assertThat(authors).containsExactly(authorOne, authorTwo);
        @SuppressWarnings("unchecked")
        List<Long> declaredAgainst = (List<Long>) model.getAttribute("declaredAgainst");
        assertThat(declaredAgainst).containsExactly(100L);
    }

    // The core of the blind-review fix: declarableAuthors must never even be called for a
    // blind-review conference, let alone have its result (every author's real name) placed in
    // the model where the template could render it.
    @Test
    void conflictsNeverExposesAuthorIdentitiesForABlindReviewConference() {
        Conference conference = new Conference();
        conference.setId(5L);
        conference.setBlindReview(true);
        when(conferenceService.getActiveConference()).thenReturn(conference);
        when(conflictDeclarationRepository.findByConferenceIdAndReviewerId(5L, 1L)).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        String view = controller.conflicts(model);

        assertThat(view).isEqualTo("review/conflicts");
        assertThat(model.containsAttribute("authors")).isFalse();
        verify(conflictDeclarationService, Mockito.never()).declarableAuthors(any());
    }

    @Test
    void declareConflictByEmailCallsServiceAndRedirects() {
        Conference conference = new Conference();
        conference.setId(5L);
        conference.setBlindReview(true);
        when(conferenceService.getActiveConference()).thenReturn(conference);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.declareConflictByEmail("author@example.com", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/review/ui/conflicts");
        verify(conflictDeclarationService).declareConflictByEmail(reviewer, conference, "author@example.com");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("message");
    }

    @Test
    void declareConflictByEmailCapturesInvalidEmailAsFlashError() {
        Conference conference = new Conference();
        conference.setId(5L);
        when(conferenceService.getActiveConference()).thenReturn(conference);
        Mockito.doThrow(new IllegalArgumentException("No author at this conference is registered with that email address."))
                .when(conflictDeclarationService).declareConflictByEmail(reviewer, conference, "nobody@example.com");

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.declareConflictByEmail("nobody@example.com", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/review/ui/conflicts");
        assertThat((Map<String, Object>) redirectAttributes.getFlashAttributes())
                .containsEntry("error", "No author at this conference is registered with that email address.");
    }

    @Test
    void declareConflictCallsServiceAndRedirects() {
        Conference conference = new Conference();
        conference.setId(5L);
        when(conferenceService.getActiveConference()).thenReturn(conference);

        User author = new User();
        author.setId(100L);
        when(userRepository.findById(100L)).thenReturn(Optional.of(author));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.declareConflict(100L, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/review/ui/conflicts");
        verify(conflictDeclarationService).declareConflict(reviewer, conference, author);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("message");
    }
}
