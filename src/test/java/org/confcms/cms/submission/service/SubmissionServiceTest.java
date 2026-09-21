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
import org.confcms.cms.submission.domain.PaperStatus;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Test
    void uploadRevisionRejectsWhenNotInRevisionStatus() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        User submitter = new User();
        submitter.setId(10L);

        Paper paper = new Paper();
        paper.setId(5L);
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.SUBMITTED);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));

        MockMultipartFile file = new MockMultipartFile("file", "revised.pdf", "application/pdf", "%PDF-1.4".getBytes());

        assertThatThrownBy(() -> service.uploadRevision(submitter, 5L, file))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void uploadRevisionAutoRejectsAndBlocksUploadWhenDeadlinePassed() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        User submitter = new User();
        submitter.setId(10L);

        Paper paper = new Paper();
        paper.setId(5L);
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.MINOR_REVISION);
        paper.setRevisionDueDate(LocalDate.now().minusDays(1));

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "revised.pdf", "application/pdf", "%PDF-1.4".getBytes());

        assertThatThrownBy(() -> service.uploadRevision(submitter, 5L, file))
                .isInstanceOf(IllegalStateException.class);

        assertThat(paper.getStatus()).isEqualTo(PaperStatus.REJECTED);
    }

    @Test
    void uploadRevisionAutoRejectClearsRevisionDueDate() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        User submitter = new User();
        submitter.setId(10L);

        Paper paper = new Paper();
        paper.setId(5L);
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.MINOR_REVISION);
        paper.setRevisionDueDate(LocalDate.now().minusDays(1));

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "revised.pdf", "application/pdf", "%PDF-1.4".getBytes());

        assertThatThrownBy(() -> service.uploadRevision(submitter, 5L, file))
                .isInstanceOf(IllegalStateException.class);

        assertThat(paper.getRevisionDueDate()).isNull();
    }

    @Test
    void uploadNewVersionBypassBlockedPastRevisionDeadline() {
        // Fix 2: the plain /version upload path (uploadNewVersion) must be subject to the same
        // revision-deadline enforcement as uploadRevision, otherwise an author could dodge the
        // auto-reject by calling the older endpoint instead of the new revision-specific one.
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        User submitter = new User();
        submitter.setId(10L);

        Paper paper = new Paper();
        paper.setId(5L);
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.MAJOR_REVISION);
        paper.setRevisionDueDate(LocalDate.now().minusDays(1));

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "revised.pdf", "application/pdf", "%PDF-1.4".getBytes());

        assertThatThrownBy(() -> service.uploadNewVersion(submitter, 5L, file))
                .isInstanceOf(IllegalStateException.class);

        assertThat(paper.getStatus()).isEqualTo(PaperStatus.REJECTED);
        assertThat(paper.getRevisionDueDate()).isNull();
        verify(fileStorageService, never()).store(any());
    }

    @Test
    void uploadNewVersionStillBlocksWithdrawnPapers() {
        // Fix 2 must not change uploadNewVersion's existing WITHDRAWN-blocking behavior.
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        User submitter = new User();
        submitter.setId(10L);

        Paper paper = new Paper();
        paper.setId(5L);
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.WITHDRAWN);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));

        MockMultipartFile file = new MockMultipartFile("file", "revised.pdf", "application/pdf", "%PDF-1.4".getBytes());

        assertThatThrownBy(() -> service.uploadNewVersion(submitter, 5L, file))
                .isInstanceOf(IllegalStateException.class);

        verify(fileStorageService, never()).store(any());
    }

    @Test
    void uploadNewVersionUnaffectedForNormalStatus() {
        // Fix 2 must not change uploadNewVersion's behavior for statuses other than
        // WITHDRAWN/MINOR_REVISION/MAJOR_REVISION.
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        User submitter = new User();
        submitter.setId(10L);

        Paper paper = new Paper();
        paper.setId(5L);
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.SUBMITTED);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(fileStorageService.store(any())).thenReturn("/uploads/v2.pdf");
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "v2.pdf", "application/pdf", "%PDF-1.4".getBytes());

        Paper result = service.uploadNewVersion(submitter, 5L, file);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.SUBMITTED);
        assertThat(result.getVersions()).hasSize(1);
    }

    @Test
    void uploadRevisionSucceedsBeforeDeadline() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        User submitter = new User();
        submitter.setId(10L);

        Paper paper = new Paper();
        paper.setId(5L);
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.MAJOR_REVISION);
        paper.setRevisionDueDate(LocalDate.now().plusDays(5));

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(fileStorageService.store(any())).thenReturn("/uploads/revised.pdf");
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "revised.pdf", "application/pdf", "%PDF-1.4".getBytes());

        Paper result = service.uploadRevision(submitter, 5L, file);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.MAJOR_REVISION);
        assertThat(result.getVersions()).hasSize(1);
    }
}
