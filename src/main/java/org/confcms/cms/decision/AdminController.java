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

    // Two passes instead of one findById() call per flagged paper inside the loop (an N+1,
    // worsened by the template also walking each match's own lazy paper.title -- a second N+1):
    // first collect every duplicate-of id across all papers, then fetch them all in one batched
    // IN query with the paper association already joined.
    private Map<Long, PaperVersion> resolveDuplicateMatches(List<Paper> papers) {
        Map<Long, Long> paperIdToDuplicateOfVersionId = new HashMap<>();
        for (Paper paper : papers) {
            paper.getVersions().stream()
                    .max(Comparator.comparing(PaperVersion::getVersionNumber))
                    .filter(PaperVersion::isPossibleDuplicate)
                    .ifPresent(latest -> paperIdToDuplicateOfVersionId.put(paper.getId(), latest.getDuplicateOfPaperVersionId()));
        }
        if (paperIdToDuplicateOfVersionId.isEmpty()) {
            return Map.of();
        }

        Map<Long, PaperVersion> duplicateOfVersionById = paperVersionRepository
                .findAllWithPaperByIdIn(paperIdToDuplicateOfVersionId.values()).stream()
                .collect(java.util.stream.Collectors.toMap(PaperVersion::getId, v -> v));

        Map<Long, PaperVersion> matches = new HashMap<>();
        paperIdToDuplicateOfVersionId.forEach((paperId, duplicateOfVersionId) -> {
            PaperVersion matched = duplicateOfVersionById.get(duplicateOfVersionId);
            if (matched != null) {
                matches.put(paperId, matched);
            }
        });
        return matches;
    }

    @GetMapping("/papers")
    public String papers(Model model) {
        model.addAttribute("papers", submissionService.getAllPapers());
        return "admin/papers";
    }
}
