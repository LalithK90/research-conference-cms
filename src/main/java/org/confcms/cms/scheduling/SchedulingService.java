package org.confcms.cms.scheduling;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.conference.ConferenceRepository;
import org.confcms.cms.paper.Paper;
import org.confcms.cms.paper.PaperRepository;
import org.confcms.cms.paper.PaperStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SchedulingService {

    private final RoomRepository roomRepository;
    private final SessionRepository sessionRepository;
    private final PresentationRepository presentationRepository;
    private final ConferenceRepository conferenceRepository;
    private final PaperRepository paperRepository;

    // --- Room Management ---
    @Transactional
    public Room createRoom(Long conferenceId, String name, Integer capacity, String location) {
        Conference conference = conferenceRepository.findById(conferenceId)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));
        
        Room room = new Room();
        room.setConference(conference);
        room.setName(name);
        room.setCapacity(capacity);
        room.setLocation(location);
        return roomRepository.save(room);
    }

    public List<Room> getRooms(Long conferenceId) {
        return roomRepository.findByConferenceId(conferenceId);
    }

    // --- Session Management ---
    @Transactional
    public Session createSession(Long conferenceId, String title, String description, LocalDateTime start, LocalDateTime end, Long roomId) {
        Conference conference = conferenceRepository.findById(conferenceId)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));
        
        Room room = null;
        if (roomId != null) {
            room = roomRepository.findById(roomId)
                    .orElseThrow(() -> new IllegalArgumentException("Room not found"));

            boolean overlaps = sessionRepository.findByRoomId(roomId).stream()
                    .anyMatch(existing -> start.isBefore(existing.getEndTime()) && existing.getStartTime().isBefore(end));
            if (overlaps) {
                throw new IllegalStateException("This room is already booked for an overlapping time");
            }
        }

        Session session = new Session();
        session.setConference(conference);
        session.setTitle(title);
        session.setDescription(description);
        session.setStartTime(start);
        session.setEndTime(end);
        session.setRoom(room);
        
        return sessionRepository.save(session);
    }

    public List<Session> getSessions(Long conferenceId) {
        return sessionRepository.findByConferenceIdOrderByStartTimeAsc(conferenceId);
    }

    // --- Presentation Management ---
    @Transactional
    public Presentation assignPaperToSession(Long paperId, Long sessionId, LocalDateTime start, LocalDateTime end) {
        Paper paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        // Only a decided, accepted paper belongs in a public program -- scheduling anything
        // earlier (still under review, or not yet decided) would put undecided or rejected
        // work in front of attendees.
        if (paper.getStatus() != PaperStatus.ACCEPTED && paper.getStatus() != PaperStatus.CAMERA_READY_SUBMITTED) {
            throw new IllegalStateException("Only accepted papers can be scheduled into a session");
        }

        // Check if paper is already presented
        if (presentationRepository.findByPaperId(paperId).isPresent()) {
            throw new IllegalStateException("Paper is already assigned to a session");
        }

        Presentation presentation = new Presentation();
        presentation.setPaper(paper);
        presentation.setSession(session);
        presentation.setStartTime(start);
        presentation.setEndTime(end);
        
        return presentationRepository.save(presentation);
    }
    
    @Transactional
    public void removePaperFromSession(Long presentationId) {
        presentationRepository.deleteById(presentationId);
    }
}
