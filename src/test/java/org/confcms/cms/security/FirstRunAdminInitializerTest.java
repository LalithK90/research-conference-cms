package org.confcms.cms.security;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirstRunAdminInitializerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private ApplicationArguments applicationArguments;

    private FirstRunAdminInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new FirstRunAdminInitializer(userRepository, passwordEncoder);
    }

    @Test
    void noOpsWhenAnAdminAlreadyExists() {
        User existingAdmin = new User();
        existingAdmin.setEmail("admin@example.com");
        when(userRepository.findByRole(Role.ADMIN)).thenReturn(List.of(existingAdmin));

        initializer.run(applicationArguments);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createsAdminWithGeneratedPasswordWhenNoneExists() {
        when(userRepository.findByRole(Role.ADMIN)).thenReturn(List.of());
        when(passwordEncoder.encode(any())).thenReturn("encoded-hash");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        initializer.run(applicationArguments);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("asakahatapitiya@gmail.com");
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getPasswordHash()).isEqualTo("encoded-hash");
        assertThat(saved.isEnabled()).isTrue();
    }
}
