package org.confcms.cms.web.controller;

import org.confcms.cms.auth.service.AuthService;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    @Mock
    private AuthService authService;

    private AdminUserController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminUserController(authService);
    }

    @Test
    void showFormReturnsTheCreateUserView() {
        String view = controller.showForm();

        assertThat(view).isEqualTo("admin/users_new");
    }

    @Test
    void createRedirectsWithSuccessOnValidSubmission() {
        when(authService.createUserDirect("new@example.com", "New Person", Role.REVIEWER))
                .thenReturn(new User());
        Model model = new ExtendedModelMap();

        String view = controller.create("new@example.com", "New Person", Role.REVIEWER, model);

        assertThat(view).isEqualTo("redirect:/admin/users/new?created=true");
    }

    @Test
    void createRejectsAdminRoleWithoutCallingAuthService() {
        Model model = new ExtendedModelMap();

        String view = controller.create("wouldbeadmin@example.com", "Someone", Role.ADMIN, model);

        assertThat(view).isEqualTo("admin/users_new");
        assertThat(model.getAttribute("error")).isEqualTo(
                "Admin accounts cannot be created without a password. "
                        + "Create the user as Author or Reviewer, then promote them separately.");
        verify(authService, never()).createUserDirect(any(), any(), any());
    }

    @Test
    void createRedisplaysFormWithErrorOnDuplicateEmail() {
        when(authService.createUserDirect(eq("taken@example.com"), any(), any()))
                .thenThrow(new IllegalArgumentException("Email already in use"));
        Model model = new ExtendedModelMap();

        String view = controller.create("taken@example.com", "Someone", Role.AUTHOR, model);

        assertThat(view).isEqualTo("admin/users_new");
        assertThat(model.getAttribute("error")).isEqualTo("Email already in use");
    }
}
