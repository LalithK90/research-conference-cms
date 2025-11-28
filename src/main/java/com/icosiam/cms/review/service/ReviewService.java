package com.icosiam.cms.review.service;

import com.icosiam.cms.domain.User;
import com.icosiam.cms.review.domain.AssignmentStatus;
import com.icosiam.cms.review.domain.Review;
import com.icosiam.cms.review.domain.ReviewAssignment;
import com.icosiam.cms.review.repository.ReviewAssignmentRepository;
import com.icosiam.cms.review.repository.ReviewRepository;
import com.icosiam.cms.submission.domain.Paper;
import com.icosiam.cms.submission.repository.PaperRepository;
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
    public Review submitReview(Long assignmentId, Integer score, String comments, String confidentialComments) {
        ReviewAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

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
