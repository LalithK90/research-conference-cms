package org.confcms.cms.conference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConferenceServiceTest {

    @Mock
    private ConferenceRepository conferenceRepository;

    private ConferenceService service;

    @BeforeEach
    void setUp() {
        service = new ConferenceService(conferenceRepository);
    }

    @Test
    void getActiveConferenceReturnsItWhenOneExists() {
        Conference conference = new Conference();
        conference.setId(1L);
        when(conferenceRepository.findByIsActiveTrue()).thenReturn(Optional.of(conference));

        assertThat(service.getActiveConference()).isEqualTo(conference);
    }

    @Test
    void getActiveConferenceThrowsNoActiveConferenceExceptionWhenNoneIsActive() {
        // A dedicated exception type, not a generic IllegalStateException, so
        // GlobalExceptionHandler can route only this specific case to its own page without
        // also catching unrelated IllegalStateExceptions thrown elsewhere in the app.
        when(conferenceRepository.findByIsActiveTrue()).thenReturn(Optional.empty());

        assertThatThrownBy(service::getActiveConference)
                .isInstanceOf(NoActiveConferenceException.class);
    }
}
