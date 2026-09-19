package org.confcms.cms.review.service;

import org.confcms.cms.domain.User;
import org.confcms.cms.review.domain.AssignmentStatus;
import org.confcms.cms.review.domain.Review;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;
import org.confcms.cms.review.repository.ReviewRepository;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.repository.PaperRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewAssignmentRepository assignmentRepository;
    private final PaperRepository paperRepository;

    @Transactional
    public Review submitReview(User reviewer, Long assignmentId, Integer score, String comments, String confidentialComments) {
        ReviewAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        if (!assignment.getReviewer().getId().equals(reviewer.getId())) {
            throw new SecurityException("Not authorized to submit a review for this assignment");
        }

        if (assignment.getStatus() == AssignmentStatus.COMPLETED) {
            throw new IllegalStateException("Review already submitted");
        }

        Review review = new Review();
        review.setPaper(assignment.getPaper());
        review.setReviewer(assignment.getReviewer());
        review.setScore(score);
        review.setComments(comments);
        review.setConfidentialComments(confidentialComments);
        
        reviewRepository.save(review);

        assignment.setStatus(AssignmentStatus.COMPLETED);
        assignmentRepository.save(assignment);

        return review;
    }
}
