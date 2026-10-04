package org.confcms.cms.auth;

import org.confcms.cms.service.EmailService;
import org.confcms.cms.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthRestControllerTest {

    @Mock
    private AuthService authService;
    @Mock
    private MagicLinkService magicLinkService;
    @Mock
    private EmailService emailService;
    @Mock
    private UserRepository userRepository;

    private AuthRestController controller;

    @Test
    void requestMagicLinkReturnsSameResponseWhenEmailIsUnknown() {
        controller = new AuthRestController(authService, magicLinkService, emailService, userRepository);
        when(magicLinkService.createMagicLinkForEmail("unknown@example.com"))
                .thenThrow(new IllegalArgumentException("No user found for email"));

        ResponseEntity<?> response = controller.requestMagicLink("unknown@example.com");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo("Magic link sent if the account exists");
        verify(emailService, never()).sendSimpleEmail(anyString(), anyString(), anyString());
    }

    @Test
    void requestMagicLinkReturnsSameResponseWhenEmailIsKnown() {
        controller = new AuthRestController(authService, magicLinkService, emailService, userRepository);
        MagicLink link = new MagicLink();
        link.setToken("tok-123");
        link.setExpiresAt(java.time.LocalDateTime.now().plusHours(2));
        when(magicLinkService.createMagicLinkForEmail("known@example.com")).thenReturn(link);

        ResponseEntity<?> response = controller.requestMagicLink("known@example.com");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo("Magic link sent if the account exists");
        verify(emailService).sendSimpleEmail(anyString(), anyString(), anyString());
    }
}
