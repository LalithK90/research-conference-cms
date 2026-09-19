package org.confcms.cms.service;

import org.confcms.cms.domain.User;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.repository.PaperRepository;
import org.confcms.cms.review.domain.Review;
import org.confcms.cms.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DecisionService {

    private final ReviewRepository reviewRepository;
    private final PaperRepository paperRepository;
    private final EmailService emailService;
    private final CommitteeService committeeService;

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

        for (Paper paper : papers) {
            List<Review> reviews = reviewRepository.findByPaperId(paper.getId());
            double avg = 0.0;
            if (!reviews.isEmpty()) {
                avg = reviews.stream().mapToDouble(Review::getScore).average().orElse(0.0);
            }

            String suggestion = "BORDERLINE";
            if (avg >= acceptThreshold) suggestion = "ACCEPT";
            else if (avg <= rejectThreshold) suggestion = "REJECT";

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
            // send acceptance email (templated)
            try {
                java.util.Map<String, Object> model = new java.util.HashMap<>();
                model.put("submitterName", paper.getSubmitter().getFullName());
                model.put("paperTitle", paper.getTitle());
                emailService.sendTemplateEmail(paper.getSubmitter().getEmail(), "Paper accepted", "email/acceptance_notification.txt", model);
            } catch (Exception ignored) {}
        } else if ("REJECT".equalsIgnoreCase(decision)) {
            paper.setStatus(PaperStatus.REJECTED);
            paperRepository.save(paper);
            try {
                emailService.sendSimpleEmail(paper.getSubmitter().getEmail(), "Paper decision",
                        "We regret to inform you that your paper '" + paper.getTitle() + "' was not accepted.");
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
}
