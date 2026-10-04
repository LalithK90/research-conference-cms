package org.confcms.cms.admin.controller;

import org.confcms.cms.paper.Paper;
import org.confcms.cms.paper.PaperVersion;
import org.confcms.cms.paper.PaperVersionRepository;
import org.confcms.cms.paper.SubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private SubmissionService submissionService;
    @Mock
    private PaperVersionRepository paperVersionRepository;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(submissionService, paperVersionRepository);
    }

    @Test
    void dashboardHasNoPendingPromptByDefault() {
        when(submissionService.getAllPapers()).thenReturn(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        String view = controller.dashboard(request, model);

        assertThat(view).isEqualTo("admin/dashboard");
        assertThat(model.getAttribute("passwordPromptPending")).isEqualTo(false);
    }

    @Test
    void dashboardReportsPendingPromptWhenSessionFlagIsSet() {
        when(submissionService.getAllPapers()).thenReturn(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("passwordPromptPending", true);
        Model model = new ExtendedModelMap();

        controller.dashboard(request, model);

        assertThat(model.getAttribute("passwordPromptPending")).isEqualTo(true);
    }

    @Test
    void dashboardHasNoDuplicateMatchesWhenNoPaperIsFlagged() {
        Paper paper = new Paper();
        paper.setId(1L);
        PaperVersion version = new PaperVersion();
        version.setPossibleDuplicate(false);
        paper.getVersions().add(version);
        when(submissionService.getAllPapers()).thenReturn(List.of(paper));
        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        controller.dashboard(request, model);

        @SuppressWarnings("unchecked")
        Map<Long, PaperVersion> matches = (Map<Long, PaperVersion>) model.getAttribute("duplicateMatches");
        assertThat(matches).isEmpty();
    }

    @Test
    void dashboardResolvesTheMatchedVersionForAFlaggedPaper() {
        Paper paper = new Paper();
        paper.setId(1L);
        PaperVersion version = new PaperVersion();
        version.setPossibleDuplicate(true);
        version.setDuplicateOfPaperVersionId(42L);
        paper.getVersions().add(version);
        when(submissionService.getAllPapers()).thenReturn(List.of(paper));

        PaperVersion matchedVersion = new PaperVersion();
        matchedVersion.setId(42L);
        when(paperVersionRepository.findById(42L)).thenReturn(Optional.of(matchedVersion));

        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        controller.dashboard(request, model);

        @SuppressWarnings("unchecked")
        Map<Long, PaperVersion> matches = (Map<Long, PaperVersion>) model.getAttribute("duplicateMatches");
        assertThat(matches).containsEntry(1L, matchedVersion);
    }
}
