package org.confcms.cms.web.controller;

import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.service.SubmissionService;
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
        saved.setConference(new org.confcms.cms.domain.Conference());
        when(submissionService.submitPaper(any(User.class), any(), any(), any(), any(), any())).thenReturn(saved);

        ResponseEntity<?> response = controller.submitPaper("Title", "Abstract", "track", authorsJson, file);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isInstanceOf(org.confcms.cms.submission.dto.PaperResponseDto.class);
        assertThat(((org.confcms.cms.submission.dto.PaperResponseDto) response.getBody()).getId()).isEqualTo(42L);
    }
}
