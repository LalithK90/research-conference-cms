package org.confcms.cms.decision;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.conference.ConferenceRepository;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.review.ReviewRepository;
import org.confcms.cms.conference.CommitteeService;
import org.confcms.cms.paper.PaperRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
    private final ConferenceRepository conferenceRepository;
    private final ProceedingsService proceedingsService;

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
        java.util.List<org.confcms.cms.paper.Paper> papers = new java.util.ArrayList<>();
        papers.addAll(paperRepository.findByStatus(org.confcms.cms.paper.PaperStatus.ACCEPTED));
        papers.addAll(paperRepository.findByStatus(org.confcms.cms.paper.PaperStatus.CAMERA_READY_SUBMITTED));

        boolean isAdmin = actingUser.getRole() == Role.ADMIN;
        java.util.List<org.confcms.cms.paper.Paper> visible;
        if (isAdmin) {
            visible = papers;
        } else {
            // One query for every conference this user chairs/co-chairs, instead of up to 2
            // queries per paper (isChairOrCoChair checks CHAIR and CO_CHAIR separately) --
            // the N+1 this replaced didn't scale past a handful of papers.
            var chairOrCoChairConferenceIds = committeeService.getChairOrCoChairConferenceIds(actingUser);
            visible = papers.stream()
                    .filter(p -> chairOrCoChairConferenceIds.contains(p.getConference().getId()))
                    .toList();
        }

        model.addAttribute("papers", visible);
        model.addAttribute("conferences", visible.stream()
                .map(org.confcms.cms.paper.Paper::getConference)
                .collect(java.util.stream.Collectors.toMap(Conference::getId, c -> c, (a, b) -> a))
                .values());
        return "admin/camera_ready";
    }

    @PostMapping("/version/{versionId}/plagiarism-check")
    public String recordPlagiarismCheck(@PathVariable Long versionId,
                                         @RequestParam(required = false) Double score,
                                         @RequestParam(required = false) String note) {
        var version = decisionService.recordPlagiarismCheck(actingUser(), versionId, score, note);
        return "redirect:/admin/decisions/ui/paper/" + version.getPaper().getId();
    }

    @GetMapping("/proceedings/{conferenceId}/pdf")
    public ResponseEntity<?> downloadProceedingsPdf(@PathVariable Long conferenceId) throws IOException {
        Conference conference = authorizedConferenceOrThrow(conferenceId);

        File tempFile = File.createTempFile("proceedings-" + conferenceId, ".pdf");
        try {
            proceedingsService.generateProceedings(conference, tempFile.getAbsolutePath());
            byte[] content = Files.readAllBytes(tempFile.toPath());
            String filename = "proceedings-" + conferenceId + ".pdf";
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                    .body(content);
        } finally {
            tempFile.delete();
        }
    }

    @GetMapping("/proceedings/{conferenceId}/bibtex")
    public ResponseEntity<?> downloadBibTeX(@PathVariable Long conferenceId) throws IOException {
        Conference conference = authorizedConferenceOrThrow(conferenceId);

        File tempFile = File.createTempFile("proceedings-" + conferenceId, ".bib");
        try {
            proceedingsService.exportBibTeX(conference, tempFile.getAbsolutePath());
            byte[] content = Files.readAllBytes(tempFile.toPath());
            String filename = "proceedings-" + conferenceId + ".bib";
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                    .body(content);
        } finally {
            tempFile.delete();
        }
    }

    // ADMIN may generate proceedings for any conference; a chair/co-chair only for their own --
    // same trust boundary as cameraReadyStatus() above, applied per-conference since these
    // endpoints take a specific conferenceId directly rather than filtering a pre-fetched list.
    private Conference authorizedConferenceOrThrow(Long conferenceId) {
        Conference conference = conferenceRepository.findById(conferenceId)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));

        User actingUser = actingUser();
        boolean isAdmin = actingUser.getRole() == Role.ADMIN;
        if (!isAdmin && !committeeService.isChairOrCoChair(actingUser, conference)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Not authorized to generate proceedings for this conference");
        }
        return conference;
    }
}
