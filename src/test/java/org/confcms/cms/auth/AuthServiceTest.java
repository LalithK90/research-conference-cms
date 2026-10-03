package org.confcms.cms.auth;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.user.User;
import org.confcms.cms.useridentity.UserIdentity;
import org.confcms.cms.useridentity.UserIdentityRepository;
import org.confcms.cms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserIdentityRepository userIdentityRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, userIdentityRepository, passwordEncoder);
    }

    @Test
    void registerUserRejectsExistingEmail() {
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> service.registerUser("author@example.com", "pw", "Author", Role.AUTHOR))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
        verify(userIdentityRepository, never()).save(any());
    }

    @Test
    void registerUserCreatesUserAndLocalIdentity() {
        when(userRepository.findByEmail("newperson@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Passw0rd!")).thenReturn("hashed-value");
        when(userRepository.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        when(userIdentityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.registerUser("newperson@example.com", "Passw0rd!", "New Person", Role.AUTHOR);

        assertThat(result.getEmail()).isEqualTo("newperson@example.com");
        assertThat(result.getPasswordHash()).isEqualTo("hashed-value");
        assertThat(result.getRole()).isEqualTo(Role.AUTHOR);

        ArgumentCaptor<UserIdentity> captor = ArgumentCaptor.forClass(UserIdentity.class);
        verify(userIdentityRepository).save(captor.capture());
        assertThat(captor.getValue().getProvider()).isEqualTo("local");
        assertThat(captor.getValue().getProviderUserId()).isNull();
        assertThat(captor.getValue().getUser()).isEqualTo(result);
        assertThat(captor.getValue().getLinkedAt()).isNotNull();
    }

    @Test
    void createUserDirectRejectsMalformedEmail() {
        assertThatThrownBy(() -> service.createUserDirect("not-an-email", "Someone", Role.AUTHOR))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createUserDirectRejectsBlankFullName() {
        assertThatThrownBy(() -> service.createUserDirect("blank-name@example.com", "   ", Role.AUTHOR))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createUserDirectRejectsExistingEmail() {
        when(userRepository.findByEmail("taken@example.com")).thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> service.createUserDirect("taken@example.com", "Someone", Role.REVIEWER))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
        verify(userIdentityRepository, never()).save(any());
    }

    @Test
    void createUserDirectCreatesUserWithNoPasswordAndNoIdentity() {
        when(userRepository.findByEmail("newreviewer@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(2L);
            return u;
        });

        User result = service.createUserDirect("newreviewer@example.com", "New Reviewer", Role.REVIEWER);

        assertThat(result.getEmail()).isEqualTo("newreviewer@example.com");
        assertThat(result.getFullName()).isEqualTo("New Reviewer");
        assertThat(result.getRole()).isEqualTo(Role.REVIEWER);
        assertThat(result.getPasswordHash()).isNull();
        assertThat(result.isEnabled()).isTrue();

        verify(userIdentityRepository, never()).save(any());
    }
}
