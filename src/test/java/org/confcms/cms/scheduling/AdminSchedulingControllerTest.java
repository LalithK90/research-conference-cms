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
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSchedulingControllerTest {

    @Mock
    private SchedulingService schedulingService;
    @Mock
    private ConferenceRepository conferenceRepository;
    @Mock
    private PaperRepository paperRepository;
    @Mock
    private PresentationRepository presentationRepository;

    private AdminSchedulingController controller;
    private Conference conference;

    @BeforeEach
    void setUp() {
        controller = new AdminSchedulingController(schedulingService, conferenceRepository, paperRepository, presentationRepository);
        conference = new Conference();
        conference.setId(1L);
        conference.setTitle("Test Conf");
    }

    @Test
    void programShowsOnlyAcceptedAndCameraReadyPapersNotAlreadyPresented() {
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        when(schedulingService.getRooms(1L)).thenReturn(List.of());
        when(schedulingService.getSessions(1L)).thenReturn(List.of());

        Paper accepted = new Paper();
        accepted.setId(10L);
        Paper cameraReady = new Paper();
        cameraReady.setId(11L);
        Paper alreadyPresented = new Paper();
        alreadyPresented.setId(12L);

        when(paperRepository.findByConferenceIdAndStatus(1L, PaperStatus.ACCEPTED)).thenReturn(List.of(accepted, alreadyPresented));
        when(paperRepository.findByConferenceIdAndStatus(1L, PaperStatus.CAMERA_READY_SUBMITTED)).thenReturn(List.of(cameraReady));
        when(presentationRepository.findByPaperId(10L)).thenReturn(Optional.empty());
        when(presentationRepository.findByPaperId(11L)).thenReturn(Optional.empty());
        when(presentationRepository.findByPaperId(12L)).thenReturn(Optional.of(new Presentation()));

        Model model = new ExtendedModelMap();
        String view = controller.program(1L, model);

        assertThat(view).isEqualTo("admin/scheduling");
        @SuppressWarnings("unchecked")
        List<Paper> schedulable = (List<Paper>) model.getAttribute("schedulablePapers");
        assertThat(schedulable).containsExactlyInAnyOrder(accepted, cameraReady);
    }

    @Test
    void createRoomDelegatesToServiceAndRedirects() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createRoom(1L, "Main Hall", 100, "Building A", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/conference/1/scheduling");
        verify(schedulingService).createRoom(1L, "Main Hall", 100, "Building A");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("message");
    }

    @Test
    void createRoomCapturesServiceRejectionAsFlashError() {
        Mockito.doThrow(new IllegalArgumentException("Conference not found"))
                .when(schedulingService).createRoom(1L, "Main Hall", 100, "Building A");

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createRoom(1L, "Main Hall", 100, "Building A", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/conference/1/scheduling");
        assertThat((Map<String, Object>) redirectAttributes.getFlashAttributes())
                .containsEntry("error", "Conference not found");
    }

    @Test
    void createSessionCapturesRoomOverlapRejectionAsFlashError() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 9, 0);
        LocalDateTime end = LocalDateTime.of(2026, 1, 1, 10, 0);
        Mockito.doThrow(new IllegalStateException("This room is already booked for an overlapping time"))
                .when(schedulingService).createSession(1L, "Keynote", "desc", start, end, 5L);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.createSession(1L, "Keynote", "desc", start, end, 5L, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/conference/1/scheduling");
        assertThat((Map<String, Object>) redirectAttributes.getFlashAttributes())
                .containsEntry("error", "This room is already booked for an overlapping time");
    }

    @Test
    void assignPaperDelegatesToServiceAndRedirects() {
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 9, 0);
        LocalDateTime end = LocalDateTime.of(2026, 1, 1, 9, 20);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.assignPaper(1L, 10L, 20L, start, end, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/conference/1/scheduling");
        verify(schedulingService).assignPaperToSession(10L, 20L, start, end);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("message");
    }

    @Test
    void removePresentationDelegatesToServiceAndRedirects() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.removePresentation(1L, 99L, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/conference/1/scheduling");
        verify(schedulingService).removePaperFromSession(99L);
    }
}
