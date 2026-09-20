package org.confcms.cms.review.service;

import org.confcms.cms.domain.User;
import org.confcms.cms.review.domain.AssignmentStatus;
import org.confcms.cms.review.domain.Review;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.dto.PaperReviewView;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;
import org.confcms.cms.review.repository.ReviewRepository;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperVersion;
import org.confcms.cms.submission.repository.PaperRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

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

    private ReviewAssignment loadOwnedAssignment(User actingUser, Long assignmentId) {
        ReviewAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        if (!assignment.getReviewer().getId().equals(actingUser.getId())) {
            throw new SecurityException("Not authorized to view this paper");
        }

        return assignment;
    }

    @Transactional(readOnly = true)
    public Paper getPaperForAssignment(User actingUser, Long assignmentId) {
        return getOwnedAssignment(actingUser, assignmentId).getPaper();
    }

    @Transactional(readOnly = true)
    public ReviewAssignment getOwnedAssignment(User actingUser, Long assignmentId) {
        return loadOwnedAssignment(actingUser, assignmentId);
    }

    @Transactional(readOnly = true)
    public PaperReviewView toReviewView(Paper paper, ReviewAssignment assignment) {
        Integer latestVersionNumber = paper.getVersions().stream()
                .map(PaperVersion::getVersionNumber)
                .max(Comparator.naturalOrder())
                .orElse(null);

        List<String> authorNames = paper.getConference().isBlindReview()
                ? List.of()
                : paper.getAuthors().stream().map(a -> a.getFullName()).toList();

        return new PaperReviewView(
                paper.getId(),
                paper.getTitle(),
                paper.getAbstractText(),
                paper.getTrack(),
                latestVersionNumber,
                assignment.getDueDate(),
                authorNames
        );
    }

    @Transactional(readOnly = true)
    public String getLatestVersionFilePath(Paper paper) {
        return paper.getVersions().stream()
                .max(Comparator.comparing(PaperVersion::getVersionNumber))
                .map(PaperVersion::getFilePath)
                .orElseThrow(() -> new IllegalArgumentException("Paper has no uploaded version"));
    }
}
