package org.confcms.cms.paper;

import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionRestControllerTest {

    @Mock
    private SubmissionService submissionService;
    @Mock
    private UserRepository userRepository;

    private SubmissionRestController controller;

    private final MultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "%PDF-1.4".getBytes());

    @BeforeEach
    void setUp() {
        controller = new SubmissionRestController(submissionService, userRepository);

        User author = new User();
        author.setEmail("author@example.com");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(author.getEmail(), null, Collections.emptyList()));
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(author));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void submitPaperRejectsAuthorsJsonWithUnknownFieldInsteadOfSilentlyDefaulting() {
        // "isPresenter" guesses the Java field name but the DTO's real JSON property (via Lombok's
        // isPresenter()/setPresenter() accessors) is "presenter". Jackson 3's default ObjectMapper
        // would silently ignore this unknown key and leave presenter=false; FAIL_ON_UNKNOWN_PROPERTIES
        // must be re-enabled so this is rejected instead.
        String authorsJson = "[{\"fullName\":\"Ada Lovelace\",\"email\":\"ada@example.com\",\"affiliation\":\"Analytical Engines Inc\",\"isPresenter\":true}]";

        ResponseEntity<?> response = controller.submitPaper("Title", "Abstract", "track", authorsJson, file);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo("Invalid authors JSON");
    }

    @Test
    void submitPaperAcceptsAuthorsJsonWithCorrectPresenterKey() {
        String authorsJson = "[{\"fullName\":\"Ada Lovelace\",\"email\":\"ada@example.com\",\"affiliation\":\"Analytical Engines Inc\",\"presenter\":true}]";
        Paper saved = new Paper();
        saved.setId(42L);
        saved.setTitle("Title");
        saved.setConference(new org.confcms.cms.conference.Conference());
        when(submissionService.submitPaper(any(User.class), any(), any(), any(), any(), any())).thenReturn(saved);

        ResponseEntity<?> response = controller.submitPaper("Title", "Abstract", "track", authorsJson, file);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isInstanceOf(org.confcms.cms.paper.PaperResponseDto.class);
        assertThat(((org.confcms.cms.paper.PaperResponseDto) response.getBody()).getId()).isEqualTo(42L);
    }

    @Test
    void mySubmissionsReturnsCallingUsersPapers() {
        Paper paper = new Paper();
        paper.setId(1L);
        paper.setTitle("My Paper");
        paper.setConference(new org.confcms.cms.conference.Conference());
        User author = userRepository.findByEmail("author@example.com").orElseThrow();
        when(submissionService.getPapersBySubmitter(author)).thenReturn(java.util.List.of(paper));

        ResponseEntity<?> response = controller.mySubmissions();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isInstanceOf(java.util.List.class);
    }

    @Test
    void uploadVersionReturnsUpdatedPaperOnSuccess() {
        Paper saved = new Paper();
        saved.setId(5L);
        saved.setConference(new org.confcms.cms.conference.Conference());
        when(submissionService.uploadNewVersion(any(User.class), anyLong(), any())).thenReturn(saved);

        ResponseEntity<?> response = controller.uploadVersion(5L, file);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((PaperResponseDto) response.getBody()).getId()).isEqualTo(5L);
    }

    @Test
    void uploadVersionReturns403WhenRequesterNotAuthorized() {
        when(submissionService.uploadNewVersion(any(User.class), anyLong(), any()))
                .thenThrow(new SecurityException("Not authorized to upload new version"));

        ResponseEntity<?> response = controller.uploadVersion(5L, file);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isEqualTo("Not authorized to upload new version");
    }

    @Test
    void uploadVersionReturns409WhenPaperNotInCorrectStage() {
        when(submissionService.uploadNewVersion(any(User.class), anyLong(), any()))
                .thenThrow(new IllegalStateException("Cannot upload versions for withdrawn paper"));

        ResponseEntity<?> response = controller.uploadVersion(5L, file);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isEqualTo("Cannot upload versions for withdrawn paper");
    }

    @Test
    void uploadRevisionReturnsUpdatedPaperOnSuccess() {
        Paper saved = new Paper();
        saved.setId(6L);
        saved.setConference(new org.confcms.cms.conference.Conference());
        when(submissionService.uploadRevision(any(User.class), anyLong(), any())).thenReturn(saved);

        ResponseEntity<?> response = controller.uploadRevision(6L, file);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((PaperResponseDto) response.getBody()).getId()).isEqualTo(6L);
    }

    @Test
    void uploadRevisionReturns403WhenRequesterNotAuthorized() {
        when(submissionService.uploadRevision(any(User.class), anyLong(), any()))
                .thenThrow(new SecurityException("Not authorized to upload a revision for this paper"));

        ResponseEntity<?> response = controller.uploadRevision(6L, file);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isEqualTo("Not authorized to upload a revision for this paper");
    }

    @Test
    void uploadRevisionReturns409WhenPaperNotAwaitingRevision() {
        when(submissionService.uploadRevision(any(User.class), anyLong(), any()))
                .thenThrow(new IllegalStateException("This paper is not currently awaiting a revision"));

        ResponseEntity<?> response = controller.uploadRevision(6L, file);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isEqualTo("This paper is not currently awaiting a revision");
    }

    @Test
    void uploadCameraReadyReturnsUpdatedPaperOnSuccess() {
        Paper saved = new Paper();
        saved.setId(7L);
        saved.setConference(new org.confcms.cms.conference.Conference());
        when(submissionService.uploadCameraReady(any(User.class), anyLong(), any(), anyBoolean())).thenReturn(saved);

        ResponseEntity<?> response = controller.uploadCameraReady(7L, file, true);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((PaperResponseDto) response.getBody()).getId()).isEqualTo(7L);
    }

    @Test
    void uploadCameraReadyReturns403WhenRequesterNotAuthorized() {
        when(submissionService.uploadCameraReady(any(User.class), anyLong(), any(), anyBoolean()))
                .thenThrow(new SecurityException("Not authorized to upload a camera-ready version for this paper"));

        ResponseEntity<?> response = controller.uploadCameraReady(7L, file, true);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void uploadCameraReadyReturns409WhenCopyrightNotAgreed() {
        when(submissionService.uploadCameraReady(any(User.class), anyLong(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("Copyright transfer must be agreed to before submitting the camera-ready version"));

        ResponseEntity<?> response = controller.uploadCameraReady(7L, file, false);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody())
                .isEqualTo("Copyright transfer must be agreed to before submitting the camera-ready version");
    }

    @Test
    void withdrawReturnsUpdatedPaperOnSuccess() {
        Paper saved = new Paper();
        saved.setId(8L);
        saved.setStatus(PaperStatus.WITHDRAWN);
        saved.setConference(new org.confcms.cms.conference.Conference());
        when(submissionService.withdrawPaper(any(User.class), anyLong())).thenReturn(saved);

        ResponseEntity<?> response = controller.withdraw(8L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((PaperResponseDto) response.getBody()).getId()).isEqualTo(8L);
    }

    @Test
    void withdrawReturns403WhenRequesterNotAuthorized() {
        when(submissionService.withdrawPaper(any(User.class), anyLong()))
                .thenThrow(new SecurityException("Not authorized to withdraw this paper"));

        ResponseEntity<?> response = controller.withdraw(8L);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isEqualTo("Not authorized to withdraw this paper");
    }
}
