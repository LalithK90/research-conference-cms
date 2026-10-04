package org.confcms.cms.web.controller;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.review.repository.ReviewRepository;
import org.confcms.cms.conference.CommitteeService;
import org.confcms.cms.service.DecisionService;
import org.confcms.cms.submission.repository.PaperRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin/decisions")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
public class AdminDecisionViewController {

    private final DecisionService decisionService;
    private final PaperRepository paperRepository;
    private final ReviewRepository reviewRepository;
    private final CommitteeService committeeService;
    private final UserRepository userRepository;

    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }

    @GetMapping("/ui")
    public String ui(Model model) {
        model.addAttribute("suggestions", decisionService.suggestDecisions(3.5, 2.5));
        return "admin/decisions";
    }

    @GetMapping("/ui/paper/{id}")
    public String paperDetail(@PathVariable Long id, Model model) {
        var paperOpt = paperRepository.findById(id);
        if (paperOpt.isEmpty()) {
            model.addAttribute("error", "Paper not found");
            return "admin/paper_detail";
        }

        var paper = paperOpt.get();

        User actingUser = actingUser();
        boolean isAdmin = actingUser.getRole() == Role.ADMIN;
        if (!isAdmin && !committeeService.isChairOrCoChair(actingUser, paper.getConference())) {
            model.addAttribute("error", "Not authorized to view this paper");
            return "admin/paper_detail";
        }

        var reviews = reviewRepository.findByPaperId(id);

        model.addAttribute("paper", paper);
        model.addAttribute("reviews", reviews);
        return "admin/paper_detail";
    }

    @GetMapping("/ui/camera-ready")
    public String cameraReadyStatus(Model model) {
        User actingUser = actingUser();
        java.util.List<org.confcms.cms.submission.domain.Paper> papers = new java.util.ArrayList<>();
        papers.addAll(paperRepository.findByStatus(org.confcms.cms.submission.domain.PaperStatus.ACCEPTED));
        papers.addAll(paperRepository.findByStatus(org.confcms.cms.submission.domain.PaperStatus.CAMERA_READY_SUBMITTED));

        boolean isAdmin = actingUser.getRole() == Role.ADMIN;
        java.util.List<org.confcms.cms.submission.domain.Paper> visible = isAdmin ? papers : papers.stream()
                .filter(p -> committeeService.isChairOrCoChair(actingUser, p.getConference()))
                .toList();

        model.addAttribute("papers", visible);
        return "admin/camera_ready";
    }

    @PostMapping("/version/{versionId}/plagiarism-check")
    public String recordPlagiarismCheck(@PathVariable Long versionId,
                                         @RequestParam(required = false) Double score,
                                         @RequestParam(required = false) String note) {
        var version = decisionService.recordPlagiarismCheck(actingUser(), versionId, score, note);
        return "redirect:/admin/decisions/ui/paper/" + version.getPaper().getId();
    }
}
