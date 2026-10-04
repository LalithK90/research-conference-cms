package org.confcms.cms.auth;

import org.confcms.cms.accesslog.AccessLogService;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.security.CustomOAuth2UserService;
import org.confcms.cms.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrcidSignupControllerTest {

    @Mock
    private CustomOAuth2UserService customOAuth2UserService;
    @Mock
    private AccessLogService accessLogService;

    private OrcidSignupController controller;

    @BeforeEach
    void setUp() {
        controller = new OrcidSignupController(customOAuth2UserService, accessLogService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void formRedirectsToLoginWhenNoPendingSignupInSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        String view = controller.form(request, model);

        assertThat(view).isEqualTo("redirect:/login?error");
    }

    @Test
    void formShowsOrcidIdWhenPendingSignupExists() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute("pendingOrcidSignupId", "0000-0002-1825-0097");
        Model model = new ExtendedModelMap();

        String view = controller.form(request, model);

        assertThat(view).isEqualTo("auth/complete_orcid_signup");
        assertThat(model.getAttribute("orcidId")).isEqualTo("0000-0002-1825-0097");
    }

    @Test
    void submitRedirectsToLoginWhenNoPendingSignupInSession() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        Model model = new ExtendedModelMap();

        String view = controller.submit("new@example.com", request, response, model);

        assertThat(view).isEqualTo("redirect:/login?error");
        verify(customOAuth2UserService, never()).completeOrcidSignup(any(), any(), any());
    }

    @Test
    void submitCreatesAccountAndRedirectsToDashboardOnSuccess() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute("pendingOrcidSignupId", "0000-0002-1825-0097");
        request.getSession().setAttribute("pendingOrcidSignupName", "New Researcher");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Model model = new ExtendedModelMap();

        User created = new User();
        created.setId(10L);
        created.setEmail("new@example.com");
        created.setRole(Role.AUTHOR);
        when(customOAuth2UserService.completeOrcidSignup("0000-0002-1825-0097", "New Researcher", "new@example.com"))
                .thenReturn(created);

        String view = controller.submit("new@example.com", request, response, model);

        assertThat(view).isEqualTo("redirect:/dashboard");
        assertThat(request.getSession().getAttribute("pendingOrcidSignupId")).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("new@example.com");
        verify(accessLogService).logLogin(created, request);
    }

    @Test
    void submitShowsErrorAndKeepsSessionWhenEmailAlreadyTaken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession().setAttribute("pendingOrcidSignupId", "0000-0002-1825-0097");
        request.getSession().setAttribute("pendingOrcidSignupName", "New Researcher");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Model model = new ExtendedModelMap();

        when(customOAuth2UserService.completeOrcidSignup("0000-0002-1825-0097", "New Researcher", "taken@example.com"))
                .thenThrow(new IllegalArgumentException("An account with this email already exists."));

        String view = controller.submit("taken@example.com", request, response, model);

        assertThat(view).isEqualTo("auth/complete_orcid_signup");
        assertThat(model.getAttribute("error")).isEqualTo("An account with this email already exists.");
        assertThat(request.getSession().getAttribute("pendingOrcidSignupId")).isEqualTo("0000-0002-1825-0097");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
