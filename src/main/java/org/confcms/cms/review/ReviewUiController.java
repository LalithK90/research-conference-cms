package org.confcms.cms.review;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.conference.ConferenceService;
import org.confcms.cms.conflictdeclaration.ConflictDeclarationRepository;
import org.confcms.cms.conflictdeclaration.ConflictDeclarationService;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

// Thymeleaf front end for the reviewer workflow that ReviewRestController already exposes as a
// JSON API -- this controller calls the same underlying services directly rather than making an
// HTTP call back into the REST layer, matching the pattern AdminDecisionViewController already
// uses for DecisionService. Reviewer bidding (submitBid) is intentionally out of scope here: it
// has no REST endpoint either, so wiring it up is a separate, larger task.
@Controller
@RequestMapping("/review/ui")
@PreAuthorize("hasRole('REVIEWER')")
@RequiredArgsConstructor
public class ReviewUiController {

    private final ReviewAssignmentRepository assignmentRepository;
    private final ReviewService reviewService;
    private final ReviewAssignmentService reviewAssignmentService;
    private final UserRepository userRepository;
    private final ConferenceService conferenceService;
    private final ConflictDeclarationService conflictDeclarationService;
    private final ConflictDeclarationRepository conflictDeclarationRepository;

    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }

    @GetMapping
    public String myAssignments(Model model) {
        List<ReviewAssignment> assignments = assignmentRepository.findByReviewerId(actingUser().getId());
        model.addAttribute("assignments", assignments);
        return "review/my_assignments";
    }

    @GetMapping("/assignment/{id}")
    public String assignmentDetail(@PathVariable Long id, Model model) {
        User actingUser = actingUser();
        ReviewAssignment assignment;
        try {
            assignment = reviewService.getOwnedAssignment(actingUser, id);
        } catch (SecurityException se) {
            throw new AccessDeniedException(se.getMessage());
        } catch (IllegalArgumentException iae) {
            model.addAttribute("error", iae.getMessage());
            return "review/assignment_detail";
        }

        PaperReviewView paperView = reviewService.toReviewView(assignment.getPaper(), assignment);
        model.addAttribute("assignment", assignment);
        model.addAttribute("paper", paperView);
        model.addAttribute("reviewers", userRepository.findByRole(Role.REVIEWER).stream()
                .filter(r -> !r.getId().equals(actingUser.getId()))
                .toList());
        return "review/assignment_detail";
    }

    @PostMapping("/assignment/{id}/submit")
    public String submitReview(@PathVariable Long id,
                                @RequestParam Integer score,
                                @RequestParam(required = false) String comments,
                                @RequestParam(required = false) String confidentialComments,
                                RedirectAttributes redirectAttributes) {
        try {
            reviewService.submitReview(actingUser(), id, score, comments, confidentialComments);
            redirectAttributes.addFlashAttribute("message", "Review submitted. Thank you.");
            return "redirect:/review/ui";
        } catch (SecurityException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/review/ui/assignment/" + id;
        }
    }

    @PostMapping("/assignment/{id}/decline")
    public String declineAssignment(@PathVariable Long id,
                                     @RequestParam String reason,
                                     @RequestParam(required = false) Long suggestedUserId,
                                     @RequestParam(required = false) String suggestedName,
                                     @RequestParam(required = false) String suggestedEmail,
                                     RedirectAttributes redirectAttributes) {
        try {
            reviewAssignmentService.declineAssignment(actingUser(), id, reason, suggestedUserId, suggestedName, suggestedEmail);
            redirectAttributes.addFlashAttribute("message", "Assignment declined.");
            return "redirect:/review/ui";
        } catch (SecurityException | IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/review/ui/assignment/" + id;
        }
    }

    // Scoped to the active conference: declaring conflicts is meant to run once per reviewer
    // per conference cycle, ahead of bidding/assignment, per the original design spec.
    @GetMapping("/conflicts")
    public String conflicts(Model model) {
        User actingUser = actingUser();
        Conference conference = conferenceService.getActiveConference();

        model.addAttribute("conference", conference);
        model.addAttribute("declaredAgainst", conflictDeclarationRepository
                .findByConferenceIdAndReviewerId(conference.getId(), actingUser.getId()).stream()
                .map(d -> d.getDeclaredAgainstUser().getId())
                .toList());
        model.addAttribute("authors", conflictDeclarationService.declarableAuthors(conference).stream()
                .filter(a -> !a.getId().equals(actingUser.getId()))
                .toList());
        return "review/conflicts";
    }

    @PostMapping("/conflicts/declare")
    public String declareConflict(@RequestParam Long declaredAgainstUserId, RedirectAttributes redirectAttributes) {
        Conference conference = conferenceService.getActiveConference();
        User declaredAgainstUser = userRepository.findById(declaredAgainstUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        conflictDeclarationService.declareConflict(actingUser(), conference, declaredAgainstUser);
        redirectAttributes.addFlashAttribute("message", "Conflict declared.");
        return "redirect:/review/ui/conflicts";
    }
}
