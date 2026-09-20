package org.confcms.cms.web.controller;

import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.review.domain.Review;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.domain.ReviewDecline;
import org.confcms.cms.review.dto.PaperReviewView;
import org.confcms.cms.review.service.ReviewAssignmentService;
import org.confcms.cms.review.service.ReviewService;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;
import org.confcms.cms.review.repository.ReviewRepository;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.repository.PaperRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/review")
@RequiredArgsConstructor
public class ReviewRestController {

    private final ReviewAssignmentService assignmentService;
    private final ReviewService reviewService;
    private final ReviewAssignmentRepository assignmentRepository;
    private final ReviewRepository reviewRepository;
    private final UserRepository userRepository;
    private final PaperRepository paperRepository;
    private final org.confcms.cms.service.FileStorageService fileStorageService;

    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }

    @PostMapping("/assign/auto")
    @PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
    public ResponseEntity<?> autoAssign(@RequestParam Long paperId) {
        try {
            assignmentService.autoAssignReviewers(actingUser(), paperId);
            return ResponseEntity.ok("Auto-assigned reviewers");
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @PostMapping("/assign/manual")
    @PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
    public ResponseEntity<?> manualAssign(@RequestParam Long paperId, @RequestParam Long reviewerId) {
        var paper = paperRepository.findById(paperId).orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        User reviewer = userRepository.findById(reviewerId).orElseThrow(() -> new IllegalArgumentException("Reviewer not found"));
        try {
            assignmentService.assignReviewer(actingUser(), paper, reviewer);
            return ResponseEntity.ok("Reviewer assigned");
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<?> myAssignments() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
        List<ReviewAssignment> assignments = assignmentRepository.findByReviewerId(user.getId());
        return ResponseEntity.ok(assignments);
    }

    @PostMapping("/submit")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<?> submitReview(@RequestParam Long assignmentId,
                                          @RequestParam Integer score,
                                          @RequestParam(required = false) String comments,
                                          @RequestParam(required = false) String confidentialComments) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User reviewer = userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
        try {
            Review review = reviewService.submitReview(reviewer, assignmentId, score, comments, confidentialComments);
            return ResponseEntity.ok(review);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @GetMapping("/paper/{paperId}/reviews")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> reviewsForPaper(@PathVariable Long paperId) {
        List<Review> reviews = reviewRepository.findAll().stream().filter(r -> r.getPaper().getId().equals(paperId)).toList();
        return ResponseEntity.ok(reviews);
    }

    @PostMapping("/decline")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<?> declineAssignment(@RequestParam Long assignmentId,
                                               @RequestParam String reason,
                                               @RequestParam(required = false) Long suggestedUserId,
                                               @RequestParam(required = false) String suggestedName,
                                               @RequestParam(required = false) String suggestedEmail) {
        try {
            ReviewDecline decline = assignmentService.declineAssignment(actingUser(), assignmentId, reason, suggestedUserId, suggestedName, suggestedEmail);
            return ResponseEntity.ok(decline);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @GetMapping("/assignment/{assignmentId}/paper")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<?> getPaperForReview(@PathVariable Long assignmentId) {
        try {
            ReviewAssignment assignment = reviewService.getOwnedAssignment(actingUser(), assignmentId);
            PaperReviewView view = reviewService.toReviewView(assignment.getPaper(), assignment);
            return ResponseEntity.ok(view);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.status(404).body(iae.getMessage());
        }
    }

    @GetMapping("/assignment/{assignmentId}/paper/file")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<?> downloadPaperForReview(@PathVariable Long assignmentId) {
        try {
            Paper paper = reviewService.getOwnedAssignment(actingUser(), assignmentId).getPaper();
            String filePath = reviewService.getLatestVersionFilePath(paper);
            Resource resource = new UrlResource(fileStorageService.load(filePath).toUri());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"paper.pdf\"")
                    .body(resource);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.status(404).body(iae.getMessage());
        } catch (java.net.MalformedURLException e) {
            return ResponseEntity.status(404).body("File not found");
        }
    }
}
