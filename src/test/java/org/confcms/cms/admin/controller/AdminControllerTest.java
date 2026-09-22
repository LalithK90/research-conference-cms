package org.confcms.cms.admin.controller;

import org.confcms.cms.submission.service.SubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private SubmissionService submissionService;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(submissionService);
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
}
