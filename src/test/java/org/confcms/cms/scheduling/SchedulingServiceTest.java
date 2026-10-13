package org.confcms.cms.scheduling;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.conference.ConferenceRepository;
import org.confcms.cms.paper.Paper;
import org.confcms.cms.paper.PaperRepository;
import org.confcms.cms.paper.PaperStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchedulingServiceTest {

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private PresentationRepository presentationRepository;
    @Mock
    private ConferenceRepository conferenceRepository;
    @Mock
    private PaperRepository paperRepository;

    private SchedulingService service;
    private Conference conference;

    @BeforeEach
    void setUp() {
        service = new SchedulingService(roomRepository, sessionRepository, presentationRepository, conferenceRepository, paperRepository);

        conference = new Conference();
        conference.setId(1L);
    }

    @Test
    void createRoomSavesARoomForTheConference() {
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        when(roomRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Room room = service.createRoom(1L, "Main Hall", 200, "Building A");

        assertThat(room.getConference()).isEqualTo(conference);
        assertThat(room.getName()).isEqualTo("Main Hall");
        assertThat(room.getCapacity()).isEqualTo(200);
    }

    @Test
    void createRoomRejectsUnknownConference() {
        when(conferenceRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createRoom(1L, "Main Hall", 200, "Building A"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createSessionWithNoRoomSkipsOverlapCheck() {
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Session session = service.createSession(1L, "Keynote", "desc",
                LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 10, 0), null);

        assertThat(session.getRoom()).isNull();
        verify(sessionRepository, never()).findByRoomId(any());
    }

    @Test
    void createSessionRejectsOverlappingTimeInTheSameRoom() {
        Room room = new Room();
        room.setId(10L);
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        when(roomRepository.findById(10L)).thenReturn(Optional.of(room));

        Session existing = new Session();
        existing.setRoom(room);
        existing.setStartTime(LocalDateTime.of(2026, 1, 1, 9, 0));
        existing.setEndTime(LocalDateTime.of(2026, 1, 1, 11, 0));
        when(sessionRepository.findByRoomId(10L)).thenReturn(List.of(existing));

        // New session from 10:00-12:00 overlaps the existing 09:00-11:00 slot (10:00-11:00 overlap).
        assertThatThrownBy(() -> service.createSession(1L, "Track B", "desc",
                LocalDateTime.of(2026, 1, 1, 10, 0), LocalDateTime.of(2026, 1, 1, 12, 0), 10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already booked");

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void createSessionAllowsBackToBackSessionsInTheSameRoom() {
        // Exactly adjacent (one ends exactly when the other starts) is not an overlap.
        Room room = new Room();
        room.setId(10L);
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        when(roomRepository.findById(10L)).thenReturn(Optional.of(room));

        Session existing = new Session();
        existing.setRoom(room);
        existing.setStartTime(LocalDateTime.of(2026, 1, 1, 9, 0));
        existing.setEndTime(LocalDateTime.of(2026, 1, 1, 11, 0));
        when(sessionRepository.findByRoomId(10L)).thenReturn(List.of(existing));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Session session = service.createSession(1L, "Track B", "desc",
                LocalDateTime.of(2026, 1, 1, 11, 0), LocalDateTime.of(2026, 1, 1, 12, 0), 10L);

        assertThat(session.getStartTime()).isEqualTo(LocalDateTime.of(2026, 1, 1, 11, 0));
    }

    @Test
    void createSessionAllowsNonOverlappingTimeInADifferentRoom() {
        Room roomA = new Room();
        roomA.setId(10L);
        Room roomB = new Room();
        roomB.setId(20L);
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        when(roomRepository.findById(20L)).thenReturn(Optional.of(roomB));

        Session existingInRoomA = new Session();
        existingInRoomA.setRoom(roomA);
        existingInRoomA.setStartTime(LocalDateTime.of(2026, 1, 1, 9, 0));
        existingInRoomA.setEndTime(LocalDateTime.of(2026, 1, 1, 11, 0));
        when(sessionRepository.findByRoomId(20L)).thenReturn(List.of());
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Session session = service.createSession(1L, "Track B", "desc",
                LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 11, 0), 20L);

        assertThat(session.getRoom()).isEqualTo(roomB);
    }

    @Test
    void assignPaperToSessionRejectsPaperNotYetAccepted() {
        Paper paper = new Paper();
        paper.setId(5L);
        paper.setStatus(PaperStatus.SUBMITTED);

        Session session = new Session();
        session.setId(7L);

        when(paperRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(paper));
        when(sessionRepository.findById(7L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.assignPaperToSession(5L, 7L,
                LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 9, 20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accepted");

        verify(presentationRepository, never()).save(any());
    }

    @Test
    void assignPaperToSessionAllowsAcceptedPaper() {
        Paper paper = new Paper();
        paper.setId(5L);
        paper.setStatus(PaperStatus.ACCEPTED);

        Session session = new Session();
        session.setId(7L);

        when(paperRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(paper));
        when(sessionRepository.findById(7L)).thenReturn(Optional.of(session));
        when(presentationRepository.findByPaperId(5L)).thenReturn(Optional.empty());
        when(presentationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Presentation presentation = service.assignPaperToSession(5L, 7L,
                LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 9, 20));

        assertThat(presentation.getPaper()).isEqualTo(paper);
        assertThat(presentation.getSession()).isEqualTo(session);
    }

    @Test
    void assignPaperToSessionAllowsCameraReadySubmittedPaper() {
        Paper paper = new Paper();
        paper.setId(5L);
        paper.setStatus(PaperStatus.CAMERA_READY_SUBMITTED);

        Session session = new Session();
        session.setId(7L);

        when(paperRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(paper));
        when(sessionRepository.findById(7L)).thenReturn(Optional.of(session));
        when(presentationRepository.findByPaperId(5L)).thenReturn(Optional.empty());
        when(presentationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Presentation presentation = service.assignPaperToSession(5L, 7L,
                LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 9, 20));

        assertThat(presentation.getPaper()).isEqualTo(paper);
    }

    @Test
    void assignPaperToSessionRejectsAPaperAlreadyAssignedElsewhere() {
        Paper paper = new Paper();
        paper.setId(5L);
        paper.setStatus(PaperStatus.ACCEPTED);

        Session session = new Session();
        session.setId(7L);

        when(paperRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(paper));
        when(sessionRepository.findById(7L)).thenReturn(Optional.of(session));
        when(presentationRepository.findByPaperId(5L)).thenReturn(Optional.of(new Presentation()));

        assertThatThrownBy(() -> service.assignPaperToSession(5L, 7L,
                LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 9, 20)))
                .isInstanceOf(IllegalStateException.class);
    }

    // Without a row lock, two concurrent assignPaperToSession calls for the same paper can both
    // pass the findByPaperId "already assigned" check before either commits its save -- only the
    // DB's unique constraint on presentations.paper_id stops the second from double-booking, and
    // it does so with an ugly DataIntegrityViolationException instead of this method's own clean
    // IllegalStateException. Locking the paper row (same pattern as RegistrationService.register)
    // narrows that race to the width of this transaction.
    @Test
    void assignPaperToSessionLocksThePaperRowToNarrowTheDoubleBookingRace() {
        Paper paper = new Paper();
        paper.setId(5L);
        paper.setStatus(PaperStatus.ACCEPTED);

        Session session = new Session();
        session.setId(7L);

        when(paperRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(paper));
        when(sessionRepository.findById(7L)).thenReturn(Optional.of(session));
        when(presentationRepository.findByPaperId(5L)).thenReturn(Optional.empty());
        when(presentationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.assignPaperToSession(5L, 7L,
                LocalDateTime.of(2026, 1, 1, 9, 0), LocalDateTime.of(2026, 1, 1, 9, 20));

        verify(paperRepository).findByIdForUpdate(5L);
        verify(paperRepository, never()).findById(5L);
    }

    @Test
    void getRoomsDelegatesToRepository() {
        when(roomRepository.findByConferenceId(1L)).thenReturn(List.of(new Room()));

        assertThat(service.getRooms(1L)).hasSize(1);
    }

    @Test
    void getSessionsDelegatesToRepository() {
        when(sessionRepository.findByConferenceIdOrderByStartTimeAsc(1L)).thenReturn(List.of(new Session()));

        assertThat(service.getSessions(1L)).hasSize(1);
    }

    @Test
    void removePaperFromSessionDeletesThePresentation() {
        service.removePaperFromSession(42L);

        verify(presentationRepository).deleteById(42L);
    }
}
