package org.confcms.cms.speaker;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.conference.ConferenceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSpeakerControllerTest {

    @Mock
    private SpeakerRepository speakerRepository;
    @Mock
    private ConferenceRepository conferenceRepository;

    private AdminSpeakerController controller;
    private Conference conference;

    @BeforeEach
    void setUp() {
        controller = new AdminSpeakerController(speakerRepository, conferenceRepository);
        conference = new Conference();
        conference.setId(1L);
        conference.setTitle("Test Conf");
    }

    @Test
    void listShowsSpeakersForConference() {
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        Speaker speaker = new Speaker();
        when(speakerRepository.findByConferenceIdOrderByDisplayOrderAsc(1L)).thenReturn(List.of(speaker));

        Model model = new ExtendedModelMap();
        String view = controller.list(1L, model);

        assertThat(view).isEqualTo("admin/speakers");
        assertThat((List<Object>) model.getAttribute("speakers")).containsExactly(speaker);
    }

    @Test
    void saveAttachesConferenceAndPersists() {
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        Speaker speaker = new Speaker();
        speaker.setFullName("Ada Lovelace");

        String view = controller.save(1L, speaker);

        assertThat(view).isEqualTo("redirect:/admin/conference/1/speakers");
        assertThat(speaker.getConference()).isEqualTo(conference);
        verify(speakerRepository).save(speaker);
    }

    @Test
    void saveRejectsSpeakerIdBelongingToAnotherConference() {
        Conference otherConference = new Conference();
        otherConference.setId(99L);
        Speaker existingSpeaker = new Speaker();
        existingSpeaker.setId(5L);
        existingSpeaker.setConference(otherConference);
        when(speakerRepository.findById(5L)).thenReturn(Optional.of(existingSpeaker));

        Speaker incoming = new Speaker();
        incoming.setId(5L);
        incoming.setFullName("Hijacked Speaker");

        assertThatThrownBy(() -> controller.save(1L, incoming))
                .isInstanceOf(IllegalArgumentException.class);
        verify(speakerRepository, never()).save(incoming);
    }

    @Test
    void deleteRemovesSpeakerById() {
        Speaker speaker = new Speaker();
        speaker.setId(5L);
        speaker.setConference(conference);
        when(speakerRepository.findById(5L)).thenReturn(Optional.of(speaker));

        String view = controller.delete(1L, 5L);

        assertThat(view).isEqualTo("redirect:/admin/conference/1/speakers");
        verify(speakerRepository).deleteById(5L);
    }

    @Test
    void deleteRejectsSpeakerBelongingToAnotherConference() {
        Conference otherConference = new Conference();
        otherConference.setId(99L);
        Speaker speaker = new Speaker();
        speaker.setId(5L);
        speaker.setConference(otherConference);
        when(speakerRepository.findById(5L)).thenReturn(Optional.of(speaker));

        assertThatThrownBy(() -> controller.delete(1L, 5L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(speakerRepository, never()).deleteById(5L);
    }

    @Test
    void editFormLoadsExistingSpeaker() {
        Speaker speaker = new Speaker();
        speaker.setId(5L);
        speaker.setType(SpeakerType.KEYNOTE);
        speaker.setConference(conference);
        when(speakerRepository.findById(5L)).thenReturn(Optional.of(speaker));
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));

        Model model = new ExtendedModelMap();
        String view = controller.editForm(1L, 5L, model);

        assertThat(view).isEqualTo("admin/speaker_form");
        assertThat(model.getAttribute("speaker")).isEqualTo(speaker);
    }

    @Test
    void editFormRejectsSpeakerBelongingToAnotherConference() {
        Conference otherConference = new Conference();
        otherConference.setId(99L);
        Speaker speaker = new Speaker();
        speaker.setId(5L);
        speaker.setConference(otherConference);
        when(speakerRepository.findById(5L)).thenReturn(Optional.of(speaker));

        Model model = new ExtendedModelMap();
        assertThatThrownBy(() -> controller.editForm(1L, 5L, model))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
