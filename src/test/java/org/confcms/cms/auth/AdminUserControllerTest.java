package org.confcms.cms.auth;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.List;
import java.util.Map;

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
    @Mock
    private UserRepository userRepository;

    private AdminUserController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminUserController(authService, userRepository);
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

    @Test
    void listShowsAllUsersAndRoles() {
        User user = new User();
        user.setId(1L);
        when(userRepository.findAll()).thenReturn(List.of(user));

        Model model = new ExtendedModelMap();
        String view = controller.list(model);

        assertThat(view).isEqualTo("admin/users_list");
        assertThat(model.getAttribute("users")).isEqualTo(List.of(user));
        assertThat((Role[]) model.getAttribute("roles")).containsExactly(Role.values());
    }

    @Test
    void updateRoleRedirectsWithSuccessMessage() {
        when(authService.updateRole(1L, Role.REVIEWER)).thenReturn(new User());

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateRole(1L, Role.REVIEWER, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/users");
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("message");
    }

    @Test
    void updateRoleCapturesLastAdminErrorAsFlashError() {
        Mockito.doThrow(new IllegalStateException("Can't change the last enabled admin account."))
                .when(authService).updateRole(1L, Role.AUTHOR);

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateRole(1L, Role.AUTHOR, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/users");
        assertThat((Map<String, Object>) redirectAttributes.getFlashAttributes())
                .containsEntry("error", "Can't change the last enabled admin account.");
    }

    @Test
    void updateEnabledRedirectsWithSuccessMessage() {
        when(authService.setEnabled(1L, false)).thenReturn(new User());

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.updateEnabled(1L, false, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/users");
        assertThat((Map<String, Object>) redirectAttributes.getFlashAttributes())
                .containsEntry("message", "Account disabled.");
    }
}
