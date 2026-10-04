package org.confcms.cms.submission.service;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.conference.ConferenceService;
import org.confcms.cms.service.EmailService;
import org.confcms.cms.service.FileStorageService;
import org.confcms.cms.personinvitation.PersonInvitationService;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperAuthor;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.domain.PaperVersion;
import org.confcms.cms.submission.repository.PaperRepository;
import org.confcms.cms.submission.repository.PaperVersionRepository;
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
    @Mock
    private PaperVersionRepository paperVersionRepository;

    @Test
    void submitPaperSetsConferenceFromActiveConference() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

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
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

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
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

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
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

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
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

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
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

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
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

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
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

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
    void uploadNewVersionBlocksPaperThatHasReachedCameraReadyStage() {
        // The generic /version upload path must not be usable to bypass uploadCameraReady's
        // own checks (ACCEPTED-status requirement, copyright-transfer agreement) once a paper
        // has moved past peer review.
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

        User submitter = new User();
        submitter.setId(10L);

        Paper paper = new Paper();
        paper.setId(5L);
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.ACCEPTED);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));

        MockMultipartFile file = new MockMultipartFile("file", "sneaky.pdf", "application/pdf", "%PDF-1.4".getBytes());

        assertThatThrownBy(() -> service.uploadNewVersion(submitter, 5L, file))
                .isInstanceOf(IllegalStateException.class);

        verify(fileStorageService, never()).store(any());
    }

    @Test
    void uploadNewVersionUnaffectedForNormalStatus() {
        // Fix 2 must not change uploadNewVersion's behavior for statuses other than
        // WITHDRAWN/MINOR_REVISION/MAJOR_REVISION.
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

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
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

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

    @Test
    void submitPaperFlagsPossibleDuplicateWhenContentHashMatches() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

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

        Paper otherPaper = new Paper();
        otherPaper.setId(999L);
        PaperVersion existingMatch = new PaperVersion();
        existingMatch.setId(42L);
        existingMatch.setPaper(otherPaper);
        when(paperVersionRepository.findByContentHash(any())).thenReturn(List.of(existingMatch));

        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "%PDF-1.4 identical content".getBytes());

        Paper saved = service.submitPaper(submitter, "Title", "Abstract", "Track A", file, Collections.emptyList());

        PaperVersion newVersion = saved.getVersions().get(0);
        assertThat(newVersion.isPossibleDuplicate()).isTrue();
        assertThat(newVersion.getDuplicateOfPaperVersionId()).isEqualTo(42L);
        assertThat(newVersion.getContentHash()).isNotNull();
    }

    @Test
    void uploadRevisionDoesNotFlagDuplicateAgainstItsOwnPaperPriorVersion() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

        User submitter = new User();
        submitter.setId(10L);

        Paper paper = new Paper();
        paper.setId(5L);
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.MAJOR_REVISION);
        paper.setRevisionDueDate(LocalDate.now().plusDays(5));

        PaperVersion priorVersion = new PaperVersion();
        priorVersion.setId(7L);
        priorVersion.setPaper(paper);
        paper.getVersions().add(priorVersion);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(fileStorageService.store(any())).thenReturn("/uploads/revised.pdf");
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paperVersionRepository.findByContentHash(any())).thenReturn(List.of(priorVersion));

        MockMultipartFile file = new MockMultipartFile("file", "revised.pdf", "application/pdf", "%PDF-1.4 unchanged content".getBytes());

        Paper result = service.uploadRevision(submitter, 5L, file);

        PaperVersion newVersion = result.getVersions().get(result.getVersions().size() - 1);
        assertThat(newVersion.isPossibleDuplicate()).isFalse();
        assertThat(newVersion.getDuplicateOfPaperVersionId()).isNull();
    }

    @Test
    void submitPaperDoesNotFlagDuplicateWhenNoHashMatchExists() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

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
        when(paperVersionRepository.findByContentHash(any())).thenReturn(List.of());

        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "%PDF-1.4 unique content".getBytes());

        Paper saved = service.submitPaper(submitter, "Title", "Abstract", "Track A", file, Collections.emptyList());

        PaperVersion newVersion = saved.getVersions().get(0);
        assertThat(newVersion.isPossibleDuplicate()).isFalse();
        assertThat(newVersion.getDuplicateOfPaperVersionId()).isNull();
        assertThat(newVersion.getContentHash()).isNotNull();
    }

    @Test
    void submitPaperComputesTheSameHashForIdenticalContent() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

        Conference activeConference = new Conference();
        activeConference.setTitle("Test Conf");
        activeConference.setVenue("Test Venue");
        activeConference.setStartDate(LocalDate.now());
        activeConference.setEndDate(LocalDate.now().plusDays(1));
        when(conferenceService.getActiveConference()).thenReturn(activeConference);

        User submitter = new User();
        submitter.setFullName("Jane Author");
        submitter.setEmail("jane@example.com");

        when(fileStorageService.store(any())).thenReturn("/uploads/a.pdf", "/uploads/b.pdf");
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paperVersionRepository.findByContentHash(any())).thenReturn(List.of());

        byte[] identicalBytes = "%PDF-1.4 identical bytes for hash comparison".getBytes();
        MockMultipartFile fileA = new MockMultipartFile("file", "a.pdf", "application/pdf", identicalBytes);
        MockMultipartFile fileB = new MockMultipartFile("file", "b.pdf", "application/pdf", identicalBytes);

        Paper savedA = service.submitPaper(submitter, "Title A", "Abstract", "Track A", fileA, Collections.emptyList());
        Paper savedB = service.submitPaper(submitter, "Title B", "Abstract", "Track A", fileB, Collections.emptyList());

        assertThat(savedA.getVersions().get(0).getContentHash())
                .isEqualTo(savedB.getVersions().get(0).getContentHash());
    }

    @Test
    void uploadCameraReadySetsStatusAndFlagsVersionWhenPaperIsAccepted() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

        Paper paper = new Paper();
        paper.setId(1L);
        paper.setTitle("Accepted Paper");
        paper.setStatus(PaperStatus.ACCEPTED);
        User submitter = new User();
        submitter.setId(5L);
        submitter.setEmail("author@example.com");
        submitter.setFullName("Author Name");
        paper.setSubmitter(submitter);

        when(paperRepository.findById(1L)).thenReturn(Optional.of(paper));
        MockMultipartFile file = new MockMultipartFile("file", "camera-ready.pdf", "application/pdf",
                "%PDF-1.4 fake content".getBytes());
        when(fileStorageService.store(file)).thenReturn("/uploads/camera-ready.pdf");
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Paper result = service.uploadCameraReady(submitter, 1L, file, true);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.CAMERA_READY_SUBMITTED);
        assertThat(result.getVersions()).hasSize(1);
        PaperVersion version = result.getVersions().get(0);
        assertThat(version.isCameraReady()).isTrue();
        assertThat(version.getCopyrightTransferAgreedAt()).isNotNull();
    }

    @Test
    void uploadCameraReadyRejectsWhenPaperNotAccepted() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

        Paper paper = new Paper();
        paper.setId(2L);
        paper.setStatus(PaperStatus.UNDER_REVIEW);
        User submitter = new User();
        submitter.setId(5L);
        paper.setSubmitter(submitter);

        when(paperRepository.findById(2L)).thenReturn(Optional.of(paper));
        MockMultipartFile file = new MockMultipartFile("file", "camera-ready.pdf", "application/pdf",
                "%PDF-1.4 fake content".getBytes());

        assertThatThrownBy(() -> service.uploadCameraReady(submitter, 2L, file, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not currently awaiting a camera-ready submission");
    }

    @Test
    void uploadCameraReadyRejectsWhenCopyrightNotAgreed() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

        Paper paper = new Paper();
        paper.setId(3L);
        paper.setStatus(PaperStatus.ACCEPTED);
        User submitter = new User();
        submitter.setId(5L);
        paper.setSubmitter(submitter);

        when(paperRepository.findById(3L)).thenReturn(Optional.of(paper));
        MockMultipartFile file = new MockMultipartFile("file", "camera-ready.pdf", "application/pdf",
                "%PDF-1.4 fake content".getBytes());

        assertThatThrownBy(() -> service.uploadCameraReady(submitter, 3L, file, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Copyright transfer must be agreed to");
    }

    @Test
    void uploadCameraReadyRejectsNonOwnerNonAdmin() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

        Paper paper = new Paper();
        paper.setId(4L);
        paper.setStatus(PaperStatus.ACCEPTED);
        User submitter = new User();
        submitter.setId(5L);
        paper.setSubmitter(submitter);

        User stranger = new User();
        stranger.setId(99L);
        stranger.setRole(org.confcms.cms.core.security.Role.AUTHOR);

        when(paperRepository.findById(4L)).thenReturn(Optional.of(paper));
        MockMultipartFile file = new MockMultipartFile("file", "camera-ready.pdf", "application/pdf",
                "%PDF-1.4 fake content".getBytes());

        assertThatThrownBy(() -> service.uploadCameraReady(stranger, 4L, file, true))
                .isInstanceOf(SecurityException.class);
    }
}
