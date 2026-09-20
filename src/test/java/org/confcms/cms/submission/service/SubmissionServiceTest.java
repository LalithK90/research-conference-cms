package org.confcms.cms.submission.service;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.ConferenceService;
import org.confcms.cms.service.EmailService;
import org.confcms.cms.service.FileStorageService;
import org.confcms.cms.service.PersonInvitationService;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperAuthor;
import org.confcms.cms.submission.repository.PaperRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceTest {

    @Mock
    private PaperRepository paperRepository;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private EmailService emailService;
    @Mock
    private ConferenceService conferenceService;
    @Mock
    private PersonInvitationService personInvitationService;
    @Mock
    private UserRepository userRepository;

    @Test
    void submitPaperSetsConferenceFromActiveConference() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        Conference activeConference = new Conference();
        activeConference.setTitle("Test Conf");
        activeConference.setVenue("Test Venue");
        activeConference.setStartDate(LocalDate.now());
        activeConference.setEndDate(LocalDate.now().plusDays(1));
        when(conferenceService.getActiveConference()).thenReturn(activeConference);

        User submitter = new User();
        submitter.setFullName("Jane Author");
        submitter.setEmail("jane@example.com");

        when(fileStorageService.store(any())).thenReturn("/uploads/paper.pdf");
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "%PDF-1.4".getBytes());

        Paper saved = service.submitPaper(submitter, "Title", "Abstract", "Track A", file, Collections.emptyList());

        assertThat(saved.getConference()).isEqualTo(activeConference);
    }

    @Test
    void submitPaperInvitesUnknownCoAuthor() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        Conference activeConference = new Conference();
        activeConference.setTitle("Test Conf");
        activeConference.setVenue("Test Venue");
        activeConference.setStartDate(LocalDate.now());
        activeConference.setEndDate(LocalDate.now().plusDays(1));
        when(conferenceService.getActiveConference()).thenReturn(activeConference);

        User submitter = new User();
        submitter.setFullName("Jane Author");
        submitter.setEmail("jane@example.com");

        when(fileStorageService.store(any())).thenReturn("/uploads/paper.pdf");
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        PaperAuthor coAuthor = new PaperAuthor();
        coAuthor.setFullName("Unknown CoAuthor");
        coAuthor.setEmail("unknown@example.com");
        coAuthor.setAffiliation("Some University");

        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "%PDF-1.4".getBytes());

        service.submitPaper(submitter, "Title", "Abstract", "Track A", file, List.of(coAuthor));

        verify(personInvitationService).inviteCoAuthor(any(), eq(coAuthor));
    }

    @Test
    void submitPaperDoesNotInviteKnownCoAuthor() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        Conference activeConference = new Conference();
        activeConference.setTitle("Test Conf");
        activeConference.setVenue("Test Venue");
        activeConference.setStartDate(LocalDate.now());
        activeConference.setEndDate(LocalDate.now().plusDays(1));
        when(conferenceService.getActiveConference()).thenReturn(activeConference);

        User submitter = new User();
        submitter.setFullName("Jane Author");
        submitter.setEmail("jane@example.com");

        when(fileStorageService.store(any())).thenReturn("/uploads/paper.pdf");
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User existingCoAuthor = new User();
        when(userRepository.findByEmail("known@example.com")).thenReturn(Optional.of(existingCoAuthor));

        PaperAuthor coAuthor = new PaperAuthor();
        coAuthor.setFullName("Known CoAuthor");
        coAuthor.setEmail("known@example.com");
        coAuthor.setAffiliation("Some University");

        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "%PDF-1.4".getBytes());

        service.submitPaper(submitter, "Title", "Abstract", "Track A", file, List.of(coAuthor));

        verify(personInvitationService, never()).inviteCoAuthor(any(), any());
    }
}
