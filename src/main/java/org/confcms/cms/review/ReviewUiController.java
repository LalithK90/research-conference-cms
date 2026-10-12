package org.confcms.cms.review;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.conference.ConferenceService;
import org.confcms.cms.conflictdeclaration.ConflictDeclarationRepository;
import org.confcms.cms.conflictdeclaration.ConflictDeclarationService;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.paper.Paper;
import org.confcms.cms.paper.PaperRepository;
import org.confcms.cms.paper.PaperStatus;
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
// uses for DecisionService.
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
    private final PaperRepository paperRepository;
    private final ReviewBidRepository reviewBidRepository;

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
    //
    // A blind-review conference never shows a browsable author roster here -- declarableAuthors
    // returns every author's real name, which reviewers at a blind-review conference are never
    // supposed to see (the same anonymity ReviewService.toReviewView already enforces for an
    // assigned paper's author list). Those reviewers instead type the email of someone they
    // already know outside the system, via declareConflictByEmail.
    @GetMapping("/conflicts")
    public String conflicts(Model model) {
        User actingUser = actingUser();
        Conference conference = conferenceService.getActiveConference();

        model.addAttribute("conference", conference);
        model.addAttribute("declaredAgainst", conflictDeclarationRepository
                .findByConferenceIdAndReviewerId(conference.getId(), actingUser.getId()).stream()
                .map(d -> d.getDeclaredAgainstUser().getId())
                .toList());

        if (!conference.isBlindReview()) {
            model.addAttribute("authors", conflictDeclarationService.declarableAuthors(conference).stream()
                    .filter(a -> !a.getId().equals(actingUser.getId()))
                    .toList());
        }
        return "review/conflicts";
    }

    // Enforced server-side in ConflictDeclarationService.declareConflictById, not just by
    // hiding the browsable list for a blind-review conference: a direct POST here with a
    // guessed user id must behave identically whether or not that id is actually a
    // declarable author, for the same reason declareConflictByEmail does.
    @PostMapping("/conflicts/declare")
    public String declareConflict(@RequestParam Long declaredAgainstUserId, RedirectAttributes redirectAttributes) {
        Conference conference = conferenceService.getActiveConference();
        conflictDeclarationService.declareConflictById(actingUser(), conference, declaredAgainstUserId);
        redirectAttributes.addFlashAttribute("message", "Conflict declared.");
        return "redirect:/review/ui/conflicts";
    }

    // Always the same outcome regardless of whether the email matched a real author here --
    // see ConflictDeclarationService.declareConflictByEmail for why (author-participation
    // oracle on a blind-review conference).
    @PostMapping("/conflicts/declare-by-email")
    public String declareConflictByEmail(@RequestParam String email, RedirectAttributes redirectAttributes) {
        Conference conference = conferenceService.getActiveConference();
        conflictDeclarationService.declareConflictByEmail(actingUser(), conference, email);
        redirectAttributes.addFlashAttribute("message",
                "Your declaration has been recorded and will be applied if it matches an author.");
        return "redirect:/review/ui/conflicts";
    }

    // Lists every SUBMITTED paper at the active conference the reviewer hasn't authored --
    // bidding only matters before assignment, matching autoAssignReviewers' own
    // SUBMITTED-only restriction. Never shows author names regardless of blind-review setting
    // (see BiddablePaperView): a bid is an interest signal, not a review, so identity isn't
    // needed to make the call.
    @GetMapping("/bidding")
    public String bidding(Model model) {
        User actingUser = actingUser();
        Conference conference = conferenceService.getActiveConference();

        List<Paper> submittedPapers = paperRepository.findByConferenceIdAndStatus(conference.getId(), PaperStatus.SUBMITTED);
        java.util.Map<Long, BidType> myBids = reviewBidRepository.findByReviewerId(actingUser.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(b -> b.getPaper().getId(), ReviewBid::getBidType));

        List<BiddablePaperView> papers = submittedPapers.stream()
                .filter(p -> !p.getSubmitter().getId().equals(actingUser.getId()))
                .filter(p -> p.getAuthors().stream().noneMatch(a -> a.getEmail().equalsIgnoreCase(actingUser.getEmail())))
                .map(p -> new BiddablePaperView(p.getId(), p.getTitle(), p.getAbstractText(), p.getTrack(), myBids.get(p.getId())))
                .toList();

        model.addAttribute("papers", papers);
        model.addAttribute("bidTypes", List.of(BidType.EAGER, BidType.WILLING, BidType.NEUTRAL, BidType.NOT_WILLING));
        return "review/bidding";
    }

    @PostMapping("/bidding/{paperId}")
    public String submitBid(@PathVariable Long paperId, @RequestParam BidType bidType, RedirectAttributes redirectAttributes) {
        try {
            reviewAssignmentService.submitBid(actingUser(), paperId, bidType, null);
            redirectAttributes.addFlashAttribute("message", "Bid recorded.");
        } catch (SecurityException | IllegalStateException | IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/review/ui/bidding";
    }
}
