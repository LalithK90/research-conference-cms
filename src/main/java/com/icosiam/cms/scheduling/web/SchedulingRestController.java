package com.icosiam.cms.scheduling.web;

import com.icosiam.cms.scheduling.domain.Presentation;
import com.icosiam.cms.scheduling.domain.Room;
import com.icosiam.cms.scheduling.domain.Session;
import com.icosiam.cms.scheduling.service.SchedulingService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/scheduling")
@RequiredArgsConstructor
public class SchedulingRestController {

    private final SchedulingService schedulingService;

    // --- Rooms ---
    @PostMapping("/rooms")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Room> createRoom(@RequestBody CreateRoomRequest request) {
        Room room = schedulingService.createRoom(request.getConferenceId(), request.getName(), request.getCapacity(), request.getLocation());
        return ResponseEntity.ok(room);
    }

    @GetMapping("/rooms")
    public ResponseEntity<List<Room>> getRooms(@RequestParam Long conferenceId) {
        return ResponseEntity.ok(schedulingService.getRooms(conferenceId));
    }

    // --- Sessions ---
    @PostMapping("/sessions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Session> createSession(@RequestBody CreateSessionRequest request) {
        Session session = schedulingService.createSession(
                request.getConferenceId(),
                request.getTitle(),
                request.getDescription(),
                request.getStartTime(),
                request.getEndTime(),
                request.getRoomId()
        );
        return ResponseEntity.ok(session);
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<Session>> getSessions(@RequestParam Long conferenceId) {
        return ResponseEntity.ok(schedulingService.getSessions(conferenceId));
    }

    // --- Presentations ---
    @PostMapping("/presentations")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Presentation> assignPaper(@RequestBody AssignPaperRequest request) {
        Presentation presentation = schedulingService.assignPaperToSession(
                request.getPaperId(),
                request.getSessionId(),
                request.getStartTime(),
                request.getEndTime()
        );
        return ResponseEntity.ok(presentation);
    }

    @DeleteMapping("/presentations/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removePresentation(@PathVariable Long id) {
        schedulingService.removePaperFromSession(id);
        return ResponseEntity.ok().build();
    }

    // --- DTOs ---
    @Data
    static class CreateRoomRequest {
        private Long conferenceId;
        private String name;
        private Integer capacity;
        private String location;
    }

    @Data
    static class CreateSessionRequest {
        private Long conferenceId;
        private String title;
        private String description;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Long roomId;
    }

    @Data
    static class AssignPaperRequest {
        private Long paperId;
        private Long sessionId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }
}
