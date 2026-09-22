package org.confcms.cms.web.controller;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.domain.UserIdentity;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountSettingsControllerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserIdentityRepository userIdentityRepository;

    private AccountSettingsController controller;

    @BeforeEach
    void setUp() {
        controller = new AccountSettingsController(userRepository, userIdentityRepository);
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, Collections.emptyList()));
    }

    @Test
    void showListsLinkedIdentitiesForCurrentUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail("author@example.com");
        user.setRole(Role.AUTHOR);
        authenticateAs(user);

        UserIdentity local = new UserIdentity();
        local.setProvider("local");
        UserIdentity google = new UserIdentity();
        google.setProvider("google");

        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));
        when(userIdentityRepository.findByUserId(1L)).thenReturn(List.of(local, google));

        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();
        String view = controller.show(request, model);

        assertThat(view).isEqualTo("account_settings");
        assertThat(model.getAttribute("identities")).isEqualTo(List.of(local, google));
        assertThat(model.getAttribute("user")).isEqualTo(user);
        assertThat(model.getAttribute("passwordPromptPending")).isEqualTo(false);
    }

    @Test
    void showReportsPasswordPromptPendingWhenSessionFlagIsSet() {
        User user = new User();
        user.setId(1L);
        user.setEmail("author@example.com");
        user.setRole(Role.AUTHOR);
        authenticateAs(user);

        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));
        when(userIdentityRepository.findByUserId(1L)).thenReturn(List.of());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("passwordPromptPending", true);
        Model model = new ExtendedModelMap();

        controller.show(request, model);

        assertThat(model.getAttribute("passwordPromptPending")).isEqualTo(true);
    }

    @Test
    void dismissPasswordPromptClearsTheSessionFlagAndRedirects() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("passwordPromptPending", true);

        String view = controller.dismissPasswordPrompt(request);

        assertThat(view).isEqualTo("redirect:/account");
        assertThat(request.getSession(false).getAttribute("passwordPromptPending")).isNull();
    }
}
