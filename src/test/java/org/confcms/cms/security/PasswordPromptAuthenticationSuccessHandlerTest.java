package org.confcms.cms.security;

import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordPromptAuthenticationSuccessHandlerTest {

    @Mock
    private UserRepository userRepository;

    private PasswordPromptAuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PasswordPromptAuthenticationSuccessHandler(userRepository);
    }

    @Test
    void setsSessionAttributeForPasswordlessUser() {
        User passwordless = new User();
        passwordless.setEmail("passwordless@example.com");
        passwordless.setPasswordHash(null);
        when(userRepository.findByEmail("passwordless@example.com")).thenReturn(Optional.of(passwordless));

        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.markPromptIfPasswordless(request, "passwordless@example.com");

        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getAttribute("passwordPromptPending")).isEqualTo(true);
    }

    @Test
    void setsSessionAttributeForUserWithEmptyStringPasswordHash() {
        // data-dev.sql seeds some users with password_hash = '' rather than NULL -- both
        // represent "no real credential", so both must trigger the prompt.
        User emptyHash = new User();
        emptyHash.setEmail("emptyhash@example.com");
        emptyHash.setPasswordHash("");
        when(userRepository.findByEmail("emptyhash@example.com")).thenReturn(Optional.of(emptyHash));

        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.markPromptIfPasswordless(request, "emptyhash@example.com");

        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getAttribute("passwordPromptPending")).isEqualTo(true);
    }

    @Test
    void doesNotSetSessionAttributeForUserWithPassword() {
        User withPassword = new User();
        withPassword.setEmail("hasone@example.com");
        withPassword.setPasswordHash("hashed");
        when(userRepository.findByEmail("hasone@example.com")).thenReturn(Optional.of(withPassword));

        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.markPromptIfPasswordless(request, "hasone@example.com");

        assertThat(request.getSession(false)).isNull();
    }
}
