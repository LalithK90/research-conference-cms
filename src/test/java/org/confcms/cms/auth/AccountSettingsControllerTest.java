package org.confcms.cms.auth;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.user.User;
import org.confcms.cms.useridentity.UserIdentity;
import org.confcms.cms.useridentity.UserIdentityRepository;
import org.confcms.cms.user.UserRepository;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void dismissPasswordPromptDoesNotCreateASessionWhenNoneExists() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(request.getSession(false)).isNull();

        String view = controller.dismissPasswordPrompt(request);

        assertThat(view).isEqualTo("redirect:/account");
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void unlinkIdentityRemovesItWhenUserHasAPassword() {
        User user = new User();
        user.setId(1L);
        user.setEmail("author@example.com");
        user.setPasswordHash("$2a$10$somehash");
        authenticateAs(user);
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));

        UserIdentity google = new UserIdentity();
        google.setProvider("google");
        when(userIdentityRepository.findByUserId(1L)).thenReturn(List.of(google));
        when(userIdentityRepository.findByUserIdAndProvider(1L, "google")).thenReturn(Optional.of(google));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.unlinkIdentity("google", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/account");
        verify(userIdentityRepository).delete(google);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("message");
    }

    @Test
    void unlinkIdentityRemovesOneOfSeveralEvenWithoutAPassword() {
        User user = new User();
        user.setId(1L);
        user.setEmail("author@example.com");
        user.setPasswordHash(null);
        authenticateAs(user);
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));

        UserIdentity google = new UserIdentity();
        google.setProvider("google");
        UserIdentity orcid = new UserIdentity();
        orcid.setProvider("orcid");
        when(userIdentityRepository.findByUserId(1L)).thenReturn(List.of(google, orcid));
        when(userIdentityRepository.findByUserIdAndProvider(1L, "google")).thenReturn(Optional.of(google));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.unlinkIdentity("google", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/account");
        verify(userIdentityRepository).delete(google);
    }

    @Test
    void unlinkIdentityRefusesToRemoveTheOnlySignInMethod() {
        User user = new User();
        user.setId(1L);
        user.setEmail("author@example.com");
        user.setPasswordHash(null);
        authenticateAs(user);
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));

        UserIdentity google = new UserIdentity();
        google.setProvider("google");
        when(userIdentityRepository.findByUserId(1L)).thenReturn(List.of(google));

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.unlinkIdentity("google", redirectAttributes);

        assertThat(view).isEqualTo("redirect:/account");
        verify(userIdentityRepository, never()).delete(any());
        assertThat((Map<String, Object>) redirectAttributes.getFlashAttributes())
                .containsEntry("error", "Can't unlink your only sign-in method. Set a password first, or link another account.");
    }
}
