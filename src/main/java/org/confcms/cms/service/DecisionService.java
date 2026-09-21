package org.confcms.cms.service;

import org.confcms.cms.domain.User;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.domain.RevisionResolution;
import org.confcms.cms.submission.repository.PaperRepository;
import org.confcms.cms.review.domain.AssignmentStatus;
import org.confcms.cms.review.domain.Review;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;
import org.confcms.cms.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DecisionService {

    private final ReviewRepository reviewRepository;
    private final PaperRepository paperRepository;
    private final EmailService emailService;
    private final CommitteeService committeeService;
    private final ReviewAssignmentRepository reviewAssignmentRepository;

    public enum DeskDecision { SEND_TO_REVIEW, DESK_REJECT }

    public static class DecisionSuggestion {
        public Long paperId;
        public String title;
        public double averageScore;
        public String suggestion; // ACCEPT / REJECT / BORDERLINE

        public DecisionSuggestion(Long paperId, String title, double averageScore, String suggestion) {
            this.paperId = paperId;
            this.title = title;
            this.averageScore = averageScore;
            this.suggestion = suggestion;
        }
    }

    public List<DecisionSuggestion> suggestDecisions(double acceptThreshold, double rejectThreshold) {
        List<Paper> papers = paperRepository.findAll();
        List<DecisionSuggestion> suggestions = new ArrayList<>();

        // Band scheme (highest to lowest average score): ACCEPT > MINOR_REVISION > BORDERLINE >
        // MAJOR_REVISION > REJECT. Rather than adding new threshold parameters (which would break
        // the existing 2-arg call sites in AdminDecisionController.suggestions and
        // AdminDecisionViewController.ui), the existing [rejectThreshold, acceptThreshold] gap is
        // split into equal thirds to derive the two new revision bands around the original
        // BORDERLINE middle third.
        double span = acceptThreshold - rejectThreshold;
        double minorRevisionThreshold = acceptThreshold - span / 3.0;
        double majorRevisionThreshold = rejectThreshold + span / 3.0;

        for (Paper paper : papers) {
            List<Review> reviews = reviewRepository.findByPaperId(paper.getId());
            double avg = 0.0;
            if (!reviews.isEmpty()) {
                avg = reviews.stream().mapToDouble(Review::getScore).average().orElse(0.0);
            }

            String suggestion;
            if (avg >= acceptThreshold) suggestion = "ACCEPT";
            else if (avg <= rejectThreshold) suggestion = "REJECT";
            else if (avg >= minorRevisionThreshold) suggestion = "MINOR_REVISION";
            else if (avg <= majorRevisionThreshold) suggestion = "MAJOR_REVISION";
            else suggestion = "BORDERLINE";

            suggestions.add(new DecisionSuggestion(paper.getId(), paper.getTitle(), avg, suggestion));
        }

        // sort by average descending
        return suggestions.stream()
                .sorted(Comparator.comparingDouble((DecisionSuggestion ds) -> ds.averageScore).reversed())
                .collect(Collectors.toList());
    }

    private void requireChairOrAdmin(User actingUser, Paper paper) {
        boolean isAdmin = actingUser.getRole() == Role.ADMIN;
        if (!isAdmin && !committeeService.isChairOrCoChair(actingUser, paper.getConference())) {
            throw new SecurityException("Not authorized to make decisions for this conference's papers");
        }
    }

    // Structurally excludes reviewer identity and confidentialComments -- the returned
    // list's element type has no field capable of holding either, so no downstream
    // template can accidentally render them.
    public static class AnonymizedFeedbackItem {
        public int index;
        public Integer score;
        public String comments;

        public AnonymizedFeedbackItem(int index, Integer score, String comments) {
            this.index = index;
            this.score = score;
            this.comments = comments;
        }

        @Override
        public String toString() {
            return "AnonymizedFeedbackItem{index=" + index + ", score=" + score + ", comments=" + comments + "}";
        }
    }

    private List<AnonymizedFeedbackItem> buildAnonymizedFeedback(Long paperId) {
        List<Review> reviews = reviewRepository.findByPaperId(paperId);
        List<AnonymizedFeedbackItem> feedback = new ArrayList<>();
        int index = 1;
        for (Review review : reviews) {
            feedback.add(new AnonymizedFeedbackItem(index, review.getScore(), review.getComments()));
            index++;
        }
        return feedback;
    }

    @Transactional
    public Paper deskReview(User actingUser, Long paperId, DeskDecision decision) {
        Paper paper = paperRepository.findById(paperId).orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        requireChairOrAdmin(actingUser, paper);

        if (decision == DeskDecision.SEND_TO_REVIEW) {
            paper.setStatus(PaperStatus.UNDER_REVIEW);
        } else {
            paper.setStatus(PaperStatus.DESK_REJECTED);
            try {
                emailService.sendSimpleEmail(paper.getSubmitter().getEmail(), "Paper decision",
                        "We regret to inform you that your paper '" + paper.getTitle() + "' did not pass desk review.");
            } catch (Exception ignored) {}
        }

        return paperRepository.save(paper);
    }

    @Transactional
    public Paper applyDecision(User actingUser, Long paperId, String decision) {
        Paper paper = paperRepository.findById(paperId).orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        requireChairOrAdmin(actingUser, paper);

        if ("ACCEPT".equalsIgnoreCase(decision)) {
            paper.setStatus(PaperStatus.ACCEPTED);
            paperRepository.save(paper);
            try {
                Map<String, Object> model = new HashMap<>();
                model.put("submitterName", paper.getSubmitter().getFullName());
                model.put("paperTitle", paper.getTitle());
                model.put("feedback", buildAnonymizedFeedback(paperId));
                emailService.sendTemplateEmail(paper.getSubmitter().getEmail(), "Paper accepted", "email/acceptance_notification.txt", model);
            } catch (Exception ignored) {}
        } else if ("REJECT".equalsIgnoreCase(decision)) {
            paper.setStatus(PaperStatus.REJECTED);
            paperRepository.save(paper);
            try {
                Map<String, Object> model = new HashMap<>();
                model.put("submitterName", paper.getSubmitter().getFullName());
                model.put("paperTitle", paper.getTitle());
                model.put("feedback", buildAnonymizedFeedback(paperId));
                emailService.sendTemplateEmail(paper.getSubmitter().getEmail(), "Paper decision", "email/rejection_notification.txt", model);
            } catch (Exception ignored) {}
        } else if ("BORDERLINE".equalsIgnoreCase(decision)) {
            paper.setStatus(PaperStatus.UNDER_REVIEW);
            paperRepository.save(paper);
        } else {
            throw new IllegalArgumentException("Unknown decision: " + decision);
        }

        return paper;
    }

    @Transactional
    public List<Paper> applyBulkDecision(User actingUser, List<Long> paperIds, String decision) {
        List<Paper> updated = new ArrayList<>();
        for (Long id : paperIds) {
            updated.add(applyDecision(actingUser, id, decision));
        }
        return updated;
    }

    @Transactional
    public Paper requestRevision(User actingUser, Long paperId, PaperStatus revisionType, LocalDate dueDate) {
        if (revisionType != PaperStatus.MINOR_REVISION && revisionType != PaperStatus.MAJOR_REVISION) {
            throw new IllegalArgumentException("revisionType must be MINOR_REVISION or MAJOR_REVISION");
        }
        // "today" does not count as a valid due date -- a due date the author receives the
        // notification email on and that is already "due" gives them no real window to act.
        if (dueDate == null || !dueDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("dueDate must be a future date");
        }

        Paper paper = paperRepository.findById(paperId).orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        requireChairOrAdmin(actingUser, paper);

        // Only a paper that has been through review, or is already mid-revision (a second
        // round), can be sent into a revision state -- otherwise a withdrawn/rejected/accepted
        // paper could be dragged back into an active revision obligation.
        if (paper.getStatus() != PaperStatus.UNDER_REVIEW
                && paper.getStatus() != PaperStatus.MINOR_REVISION
                && paper.getStatus() != PaperStatus.MAJOR_REVISION) {
            throw new IllegalStateException("Paper must be under review or already awaiting revision to request a revision");
        }

        paper.setStatus(revisionType);
        paper.setRevisionDueDate(dueDate);
        paper.setRevisionRequestedAtVersionCount(paper.getVersions().size());
        Paper saved = paperRepository.save(paper);

        try {
            Map<String, Object> model = new HashMap<>();
            model.put("submitterName", paper.getSubmitter().getFullName());
            model.put("paperTitle", paper.getTitle());
            model.put("revisionType", revisionType == PaperStatus.MINOR_REVISION ? "minor revision" : "major revision");
            model.put("dueDate", dueDate);
            model.put("feedback", buildAnonymizedFeedback(paperId));
            emailService.sendTemplateEmail(paper.getSubmitter().getEmail(), "Revision requested", "email/revision_requested_notification.txt", model);
        } catch (Exception ignored) {}

        return saved;
    }

    @Transactional
    public Paper resolveRevision(User actingUser, Long paperId, RevisionResolution resolution) {
        Paper paper = paperRepository.findById(paperId).orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        requireChairOrAdmin(actingUser, paper);

        if (paper.getStatus() != PaperStatus.MINOR_REVISION && paper.getStatus() != PaperStatus.MAJOR_REVISION) {
            throw new IllegalStateException("Paper is not currently awaiting a revision resolution");
        }

        Integer requestedAtCount = paper.getRevisionRequestedAtVersionCount();
        if (requestedAtCount == null || paper.getVersions().size() <= requestedAtCount) {
            throw new IllegalStateException("No revised version has been uploaded yet for this paper");
        }

        if (resolution == RevisionResolution.ACCEPT_DIRECTLY) {
            paper.setStatus(PaperStatus.ACCEPTED);
            paper.setRevisionDueDate(null);
            paper.setRevisionRequestedAtVersionCount(null);
            Paper saved = paperRepository.save(paper);
            try {
                Map<String, Object> model = new HashMap<>();
                model.put("submitterName", paper.getSubmitter().getFullName());
                model.put("paperTitle", paper.getTitle());
                model.put("feedback", buildAnonymizedFeedback(paperId));
                emailService.sendTemplateEmail(paper.getSubmitter().getEmail(), "Paper accepted", "email/acceptance_notification.txt", model);
            } catch (Exception ignored) {}
            return saved;
        } else {
            paper.setStatus(PaperStatus.UNDER_REVIEW);
            paper.setRevisionDueDate(null);
            paper.setRevisionRequestedAtVersionCount(null);
            Paper saved = paperRepository.save(paper);

            // Only reset assignments for reviewers who actually reviewed the pre-revision version
            // (COMPLETED). Reviewers who DECLINED explicitly opted out and must not be resurrected;
            // already-PENDING assignments need no change.
            List<ReviewAssignment> originalAssignments = reviewAssignmentRepository.findByPaperId(paperId);
            for (ReviewAssignment assignment : originalAssignments) {
                if (assignment.getStatus() == AssignmentStatus.COMPLETED) {
                    assignment.setStatus(AssignmentStatus.PENDING);
                    reviewAssignmentRepository.save(assignment);
                }
            }

            return saved;
        }
    }
}
