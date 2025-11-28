package com.icosiam.cms.web.controller;

import com.icosiam.cms.domain.User;
import com.icosiam.cms.repository.UserRepository;
import com.icosiam.cms.review.domain.Review;
import com.icosiam.cms.review.domain.ReviewAssignment;
import com.icosiam.cms.review.service.ReviewAssignmentService;
import com.icosiam.cms.review.service.ReviewService;
import com.icosiam.cms.review.repository.ReviewAssignmentRepository;
import com.icosiam.cms.review.repository.ReviewRepository;
import com.icosiam.cms.submission.repository.PaperRepository;
import lombok.RequiredArgsConstructor;
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

    @PostMapping("/assign/auto")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> autoAssign(@RequestParam Long paperId) {
        assignmentService.autoAssignReviewers(paperId);
        return ResponseEntity.ok("Auto-assigned reviewers");
    }

    @PostMapping("/assign/manual")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> manualAssign(@RequestParam Long paperId, @RequestParam Long reviewerId) {
        var paper = paperRepository.findById(paperId).orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        User reviewer = userRepository.findById(reviewerId).orElseThrow(() -> new IllegalArgumentException("Reviewer not found"));
        assignmentService.assignReviewer(paper, reviewer);
        return ResponseEntity.ok("Reviewer assigned");
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
        Review review = reviewService.submitReview(assignmentId, score, comments, confidentialComments);
        return ResponseEntity.ok(review);
    }

    @GetMapping("/paper/{paperId}/reviews")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> reviewsForPaper(@PathVariable Long paperId) {
        List<Review> reviews = reviewRepository.findAll().stream().filter(r -> r.getPaper().getId().equals(paperId)).toList();
        return ResponseEntity.ok(reviews);
    }
}
