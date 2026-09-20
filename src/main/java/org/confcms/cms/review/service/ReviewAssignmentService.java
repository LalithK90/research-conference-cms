package org.confcms.cms.review.service;

import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.review.domain.AssignmentStatus;
import org.confcms.cms.review.domain.BidType;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.domain.ReviewBid;
import org.confcms.cms.review.domain.ReviewDecline;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;
import org.confcms.cms.review.repository.ReviewBidRepository;
import org.confcms.cms.review.repository.ReviewDeclineRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.ConflictDeclarationService;
import org.confcms.cms.service.PersonInvitationService;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.repository.PaperRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewAssignmentService {

    private final ReviewAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final PaperRepository paperRepository;
    private final ReviewBidRepository bidRepository;
    private final CommitteeService committeeService;
    private final ReviewDeclineRepository reviewDeclineRepository;
    private final PersonInvitationService personInvitationService;
    private final ConflictDeclarationService conflictDeclarationService;

    private void requireChairOrAdmin(User actingUser, Paper paper) {
        boolean isAdmin = actingUser.getRole() == Role.ADMIN;
        if (!isAdmin && !committeeService.isChairOrCoChair(actingUser, paper.getConference())) {
            throw new SecurityException("Not authorized to manage reviewer assignments for this conference");
        }
    }

    @Transactional
    public void submitBid(User reviewer, Long paperId, BidType bidType, String conflictReason) {
        Paper paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new IllegalArgumentException("Paper not found"));

        ReviewBid bid = bidRepository.findByReviewerIdAndPaperId(reviewer.getId(), paperId)
                .orElse(new ReviewBid());

        bid.setReviewer(reviewer);
        bid.setPaper(paper);
        bid.setBidType(bidType);
        bid.setConflictReason(conflictReason);

        bidRepository.save(bid);
    }

    @Transactional
    public ReviewDecline declineAssignment(User actingUser, Long assignmentId, String reason,
                                            Long suggestedUserId, String suggestedName, String suggestedEmail) {
        ReviewAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        if (!assignment.getReviewer().getId().equals(actingUser.getId())) {
            throw new SecurityException("Not authorized to decline this assignment");
        }

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to decline an assignment");
        }

        boolean hasExistingUserSuggestion = suggestedUserId != null;
        boolean hasExternalSuggestion = suggestedName != null && !suggestedName.isBlank()
                && suggestedEmail != null && !suggestedEmail.isBlank();

        if (hasExistingUserSuggestion == hasExternalSuggestion) {
            throw new IllegalArgumentException(
                    "Exactly one of an existing reviewer or a name+email suggestion is required");
        }

        assignment.setStatus(AssignmentStatus.DECLINED);
        assignmentRepository.save(assignment);

        ReviewDecline decline = new ReviewDecline();
        decline.setAssignment(assignment);
        decline.setReason(reason);

        if (hasExistingUserSuggestion) {
            User suggestedUser = userRepository.findById(suggestedUserId)
                    .orElseThrow(() -> new IllegalArgumentException("Suggested reviewer not found"));
            decline.setSuggestedUser(suggestedUser);
        } else {
            decline.setSuggestedName(suggestedName);
            decline.setSuggestedEmail(suggestedEmail);
        }

        ReviewDecline saved = reviewDeclineRepository.save(decline);

        if (hasExternalSuggestion) {
            personInvitationService.createReviewerSuggestionInvitation(
                    saved, assignment.getPaper().getConference(), suggestedName, suggestedEmail);
        }

        return saved;
    }

    @Transactional
    public void autoAssignReviewers(User actingUser, Long paperId) {
        Paper paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        requireChairOrAdmin(actingUser, paper);

        if (paper.getStatus() != PaperStatus.SUBMITTED) {
            throw new IllegalStateException("Paper must be in SUBMITTED state to assign reviewers");
        }

        List<User> reviewers = userRepository.findByRole(Role.REVIEWER);
        List<ReviewBid> bids = bidRepository.findByPaperId(paperId);
        Map<Long, BidType> reviewerBids = bids.stream()
                .collect(Collectors.toMap(b -> b.getReviewer().getId(), ReviewBid::getBidType));

        // Sort reviewers by score
        // Score = Bid Score - (Load * 2)
        reviewers.sort((r1, r2) -> {
            int score1 = calculateScore(r1, reviewerBids.get(r1.getId()));
            int score2 = calculateScore(r2, reviewerBids.get(r2.getId()));
            return Integer.compare(score2, score1); // Descending
        });

        // Assign top 2 reviewers
        int assignedCount = 0;
        for (User reviewer : reviewers) {
            if (assignedCount >= 2) break;

            // Skip if conflict or not willing
            BidType bid = reviewerBids.get(reviewer.getId());
            if (bid == BidType.CONFLICT || bid == BidType.NOT_WILLING) continue;

            // Skip if the reviewer has declared a conflict against any author on this paper
            boolean hasDeclaredConflict = paper.getAuthors().stream()
                    .anyMatch(paperAuthor -> {
                        var authorUser = userRepository.findByEmail(paperAuthor.getEmail());
                        return authorUser.isPresent()
                                && conflictDeclarationService.hasConflict(reviewer, paper.getConference(), authorUser.get());
                    });
            if (hasDeclaredConflict) continue;

            // Check if already assigned
            boolean alreadyAssigned = assignmentRepository.findByPaperId(paperId).stream()
                    .anyMatch(a -> a.getReviewer().getId().equals(reviewer.getId()));

            if (!alreadyAssigned) {
                assignReviewerInternal(paper, reviewer);
                assignedCount++;
            }
        }

        paper.setStatus(PaperStatus.UNDER_REVIEW);
        paperRepository.save(paper);
    }

    private int calculateScore(User reviewer, BidType bidType) {
        int score = 0;
        if (bidType != null) {
            switch (bidType) {
                case EAGER -> score += 20;
                case WILLING -> score += 10;
                case NEUTRAL -> score += 0;
                case NOT_WILLING -> score -= 100;
                case CONFLICT -> score -= 1000;
            }
        }

        // Load balancing penalty
        int currentLoad = assignmentRepository.findByReviewerId(reviewer.getId()).size();
        score -= (currentLoad * 2);

        return score;
    }

    @Transactional
    public void assignReviewer(User actingUser, Paper paper, User reviewer) {
        requireChairOrAdmin(actingUser, paper);
        assignReviewerInternal(paper, reviewer);
    }

    @Transactional
    public void assignReviewerFromInvitation(Paper paper, User reviewer) {
        assignReviewerInternal(paper, reviewer);
    }

    private void assignReviewerInternal(Paper paper, User reviewer) {
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setPaper(paper);
        assignment.setReviewer(reviewer);
        assignment.setAssignedAt(LocalDateTime.now());
        assignment.setDueDate(LocalDateTime.now().plusWeeks(2));
        assignment.setStatus(AssignmentStatus.PENDING);
        assignmentRepository.save(assignment);
    }
}
