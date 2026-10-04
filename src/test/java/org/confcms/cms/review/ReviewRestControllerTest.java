package org.confcms.cms.review;

import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.accesslog.AccessLogService;
import org.confcms.cms.service.FileStorageService;
import org.confcms.cms.paper.Paper;
import org.confcms.cms.paper.PaperVersion;
import org.confcms.cms.paper.PaperRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewRestControllerTest {

    @Mock
    private ReviewAssignmentService assignmentService;
    @Mock
    private ReviewService reviewService;
    @Mock
    private ReviewAssignmentRepository assignmentRepository;
    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PaperRepository paperRepository;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private AccessLogService accessLogService;

    private ReviewRestController controller;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        controller = new ReviewRestController(assignmentService, reviewService, assignmentRepository,
                reviewRepository, userRepository, paperRepository, fileStorageService, accessLogService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, Collections.emptyList()));
    }

    @Test
    void downloadPaperForReviewLogsTheDownloadOnSuccess() throws Exception {
        User reviewer = new User();
        reviewer.setEmail("reviewer@example.com");
        authenticateAs(reviewer);
        when(userRepository.findByEmail("reviewer@example.com")).thenReturn(Optional.of(reviewer));

        Paper paper = new Paper();
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setPaper(paper);
        when(reviewService.getOwnedAssignment(reviewer, 5L)).thenReturn(assignment);

        PaperVersion version = new PaperVersion();
        version.setId(9L);
        Path pdfFile = tempDir.resolve("paper.pdf");
        Files.write(pdfFile, "%PDF-1.4 fake".getBytes());
        version.setFilePath(pdfFile.toString());
        when(reviewService.getLatestVersion(paper)).thenReturn(version);
        when(fileStorageService.load(pdfFile.toString())).thenReturn(pdfFile);

        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<?> response = controller.downloadPaperForReview(5L, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(accessLogService).logDownload(reviewer, version, request);
    }

    @Test
    void downloadPaperForReviewDoesNotLogOnSecurityFailure() {
        User reviewer = new User();
        reviewer.setEmail("reviewer@example.com");
        authenticateAs(reviewer);
        when(userRepository.findByEmail("reviewer@example.com")).thenReturn(Optional.of(reviewer));
        when(reviewService.getOwnedAssignment(reviewer, 5L)).thenThrow(new SecurityException("Not your assignment"));

        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<?> response = controller.downloadPaperForReview(5L, request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(accessLogService, org.mockito.Mockito.never()).logDownload(any(), any(), any());
    }
}
