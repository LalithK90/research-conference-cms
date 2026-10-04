package org.confcms.cms.decision;

import jakarta.servlet.http.HttpServletRequest;
import org.confcms.cms.paper.Paper;
import org.confcms.cms.paper.PaperVersion;
import org.confcms.cms.paper.PaperVersionRepository;
import org.confcms.cms.paper.SubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final SubmissionService submissionService;
    private final PaperVersionRepository paperVersionRepository;

    @GetMapping("/dashboard")
    public String dashboard(HttpServletRequest request, Model model) {
        List<Paper> papers = submissionService.getAllPapers();
        model.addAttribute("papers", papers);
        model.addAttribute("duplicateMatches", resolveDuplicateMatches(papers));
        var session = request.getSession(false);
        model.addAttribute("passwordPromptPending", session != null && session.getAttribute("passwordPromptPending") != null);
        return "admin/dashboard";
    }

    private Map<Long, PaperVersion> resolveDuplicateMatches(List<Paper> papers) {
        Map<Long, PaperVersion> matches = new HashMap<>();
        for (Paper paper : papers) {
            paper.getVersions().stream()
                    .max(Comparator.comparing(PaperVersion::getVersionNumber))
                    .filter(PaperVersion::isPossibleDuplicate)
                    .ifPresent(latest -> paperVersionRepository.findById(latest.getDuplicateOfPaperVersionId())
                            .ifPresent(matched -> matches.put(paper.getId(), matched)));
        }
        return matches;
    }

    @GetMapping("/papers")
    public String papers(Model model) {
        model.addAttribute("papers", submissionService.getAllPapers());
        return "admin/papers";
    }
}
