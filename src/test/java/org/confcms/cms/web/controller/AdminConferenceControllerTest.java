package org.confcms.cms.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.PaymentProvider;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.ConferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminConferenceControllerTest {

    @Mock
    private ConferenceService conferenceService;
    @Mock
    private CommitteeService committeeService;
    @Mock
    private UserRepository userRepository;

    private AdminConferenceController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminConferenceController(conferenceService, committeeService, userRepository);
    }

    @Test
    void saveConferenceRejectsMissingChair() {
        AdminConferenceController.ConferenceForm form = new AdminConferenceController.ConferenceForm();
        form.setTitle("Test Conf");
        form.setVenue("Venue");
        form.setStartDate(LocalDate.now());
        form.setEndDate(LocalDate.now().plusDays(1));
        form.setContactEmail("a@b.com");
        form.setPaymentProvider(PaymentProvider.FREE);
        form.setChairUserId(null);

        assertThatThrownBy(() -> controller.saveConference(form))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveConferenceAssignsChairWhenProvided() {
        AdminConferenceController.ConferenceForm form = new AdminConferenceController.ConferenceForm();
        form.setTitle("Test Conf");
        form.setVenue("Venue");
        form.setStartDate(LocalDate.now());
        form.setEndDate(LocalDate.now().plusDays(1));
        form.setContactEmail("a@b.com");
        form.setPaymentProvider(PaymentProvider.FREE);
        form.setChairUserId(7L);

        User chairUser = new User();
        chairUser.setId(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(chairUser));
        when(conferenceService.saveConference(any())).thenAnswer(inv -> {
            Conference c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        controller.saveConference(form);

        org.mockito.Mockito.verify(committeeService).assignChair(any(), org.mockito.Mockito.eq(chairUser));
    }

    @Test
    void saveConferenceSetsBlindReviewFlag() {
        AdminConferenceController.ConferenceForm form = new AdminConferenceController.ConferenceForm();
        form.setTitle("Test Conf");
        form.setVenue("Venue");
        form.setStartDate(LocalDate.now());
        form.setEndDate(LocalDate.now().plusDays(1));
        form.setContactEmail("a@b.com");
        form.setPaymentProvider(PaymentProvider.FREE);
        form.setChairUserId(7L);
        form.setBlindReview(true);

        User chairUser = new User();
        chairUser.setId(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(chairUser));
        when(conferenceService.saveConference(any())).thenAnswer(inv -> {
            Conference c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        controller.saveConference(form);

        org.mockito.Mockito.verify(conferenceService).saveConference(
                org.mockito.ArgumentMatchers.argThat(Conference::isBlindReview));
    }
}
