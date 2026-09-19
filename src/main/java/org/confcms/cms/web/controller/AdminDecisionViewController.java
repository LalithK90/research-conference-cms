package org.confcms.cms.web.controller;

import org.confcms.cms.review.repository.ReviewRepository;
import org.confcms.cms.service.DecisionService;
import org.confcms.cms.submission.repository.PaperRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/decisions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDecisionViewController {

    private final DecisionService decisionService;
    private final PaperRepository paperRepository;
    private final ReviewRepository reviewRepository;

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
        var reviews = reviewRepository.findByPaperId(id);

        model.addAttribute("paper", paper);
        model.addAttribute("reviews", reviews);
        return "admin/paper_detail";
    }
}
