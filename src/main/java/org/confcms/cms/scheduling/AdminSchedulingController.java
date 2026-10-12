package org.confcms.cms.scheduling;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.conference.ConferenceRepository;
import org.confcms.cms.paper.Paper;
import org.confcms.cms.paper.PaperRepository;
import org.confcms.cms.paper.PaperStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

// Admin program builder: one page covering rooms, sessions, and assigning accepted papers into
// sessions, since building a real program means seeing all three together, not three separate
// CRUD screens. Calls SchedulingService directly (same pattern as every other *UiController in
// this app that fronts an existing REST-only service) rather than hitting SchedulingRestController.
@Controller
@RequestMapping("/admin/conference/{conferenceId}/scheduling")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSchedulingController {

    private final SchedulingService schedulingService;
    private final ConferenceRepository conferenceRepository;
    private final PaperRepository paperRepository;
    private final PresentationRepository presentationRepository;

    private Conference loadConference(Long conferenceId) {
        return conferenceRepository.findById(conferenceId)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));
    }

    @GetMapping
    public String program(@PathVariable Long conferenceId, Model model) {
        Conference conference = loadConference(conferenceId);

        List<Paper> schedulablePapers = Stream.concat(
                        paperRepository.findByConferenceIdAndStatus(conferenceId, PaperStatus.ACCEPTED).stream(),
                        paperRepository.findByConferenceIdAndStatus(conferenceId, PaperStatus.CAMERA_READY_SUBMITTED).stream())
                .filter(p -> presentationRepository.findByPaperId(p.getId()).isEmpty())
                .toList();

        model.addAttribute("conference", conference);
        model.addAttribute("rooms", schedulingService.getRooms(conferenceId));
        model.addAttribute("sessions", schedulingService.getSessions(conferenceId));
        model.addAttribute("schedulablePapers", schedulablePapers);
        return "admin/scheduling";
    }

    @PostMapping("/rooms")
    public String createRoom(@PathVariable Long conferenceId, @RequestParam String name,
                              @RequestParam(required = false) Integer capacity,
                              @RequestParam(required = false) String location,
                              RedirectAttributes redirectAttributes) {
        try {
            schedulingService.createRoom(conferenceId, name, capacity, location);
            redirectAttributes.addFlashAttribute("message", "Room added.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/conference/" + conferenceId + "/scheduling";
    }

    @PostMapping("/sessions")
    public String createSession(@PathVariable Long conferenceId, @RequestParam String title,
                                 @RequestParam(required = false) String description,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
                                 @RequestParam(required = false) Long roomId,
                                 RedirectAttributes redirectAttributes) {
        try {
            schedulingService.createSession(conferenceId, title, description, startTime, endTime, roomId);
            redirectAttributes.addFlashAttribute("message", "Session added.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/conference/" + conferenceId + "/scheduling";
    }

    @PostMapping("/presentations")
    public String assignPaper(@PathVariable Long conferenceId, @RequestParam Long paperId,
                               @RequestParam Long sessionId,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
                               RedirectAttributes redirectAttributes) {
        try {
            schedulingService.assignPaperToSession(paperId, sessionId, startTime, endTime);
            redirectAttributes.addFlashAttribute("message", "Paper scheduled.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/conference/" + conferenceId + "/scheduling";
    }

    @PostMapping("/presentations/{presentationId}/remove")
    public String removePresentation(@PathVariable Long conferenceId, @PathVariable Long presentationId,
                                      RedirectAttributes redirectAttributes) {
        schedulingService.removePaperFromSession(presentationId);
        redirectAttributes.addFlashAttribute("message", "Removed from program.");
        return "redirect:/admin/conference/" + conferenceId + "/scheduling";
    }
}
