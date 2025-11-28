package com.icosiam.cms.admin.controller;

import com.icosiam.cms.submission.service.SubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final SubmissionService submissionService;

    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("papers", submissionService.getAllPapers());
        return "admin/dashboard";
    }
    
    @GetMapping("/papers")
    public String papers(Model model) {
        model.addAttribute("papers", submissionService.getAllPapers());
        return "admin/papers";
    }
}
