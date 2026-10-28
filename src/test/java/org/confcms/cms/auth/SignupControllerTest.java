package org.confcms.cms.auth;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignupControllerTest {

    @Mock
    private AuthService authService;

    private SignupController controller;

    @BeforeEach
    void setUp() {
        controller = new SignupController(authService);
    }

    @Test
    void formReturnsTheSignupView() {
        Model model = new ExtendedModelMap();

        String view = controller.form(model);

        assertThat(view).isEqualTo("auth/signup");
    }

    @Test
    void submitRegistersAsAuthorAndRedirectsToLoginOnSuccess() {
        User saved = new User();
        saved.setEmail("new@example.com");
        when(authService.registerUser("new@example.com", "pw123456", "New Person", Role.AUTHOR))
                .thenReturn(saved);

        Model model = new ExtendedModelMap();
        String view = controller.submit("new@example.com", "pw123456", "New Person", model);

        assertThat(view).isEqualTo("redirect:/login?registered=true");
    }

    @Test
    void submitReShowsTheFormWithTheServiceErrorOnFailure() {
        when(authService.registerUser(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Email already in use"));

        Model model = new ExtendedModelMap();
        String view = controller.submit("taken@example.com", "pw123456", "Someone", model);

        assertThat(view).isEqualTo("auth/signup");
        assertThat(model.getAttribute("error")).isEqualTo("Email already in use");
        verify(authService).registerUser("taken@example.com", "pw123456", "Someone", Role.AUTHOR);
    }

    @Test
    void submitPreservesSubmittedFieldsWhenReShowingTheFormOnFailure() {
        when(authService.registerUser(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Email already in use"));

        Model model = new ExtendedModelMap();
        controller.submit("taken@example.com", "pw123456", "Someone", model);

        assertThat(model.getAttribute("email")).isEqualTo("taken@example.com");
        assertThat(model.getAttribute("fullName")).isEqualTo("Someone");
    }
}
