package org.confcms.cms.admin.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.confcms.cms.submission.service.SubmissionService;
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
    public String dashboard(HttpServletRequest request, Model model) {
        model.addAttribute("papers", submissionService.getAllPapers());
        var session = request.getSession(false);
        model.addAttribute("passwordPromptPending", session != null && session.getAttribute("passwordPromptPending") != null);
        return "admin/dashboard";
    }
    
    @GetMapping("/papers")
    public String papers(Model model) {
        model.addAttribute("papers", submissionService.getAllPapers());
        return "admin/papers";
    }
}
