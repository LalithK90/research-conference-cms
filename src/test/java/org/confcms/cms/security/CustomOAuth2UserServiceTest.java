package org.confcms.cms.security;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.domain.UserIdentity;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomOAuth2UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserIdentityRepository userIdentityRepository;

    private CustomOAuth2UserService service;

    @BeforeEach
    void setUp() {
        service = new CustomOAuth2UserService(userRepository, userIdentityRepository);
    }

    private ClientRegistration googleRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("test-client")
                .clientSecret("test-secret")
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .clientName("Google")
                .build();
    }

    @Test
    void resolveRejectsUnverifiedEmail() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", "google-sub-123");
        attrs.put("email", "author@example.com");
        attrs.put("email_verified", false);

        assertThatThrownBy(() -> service.resolveLocalUser("google", attrs))
                .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);

        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void resolveAutoLinksToExistingUserByVerifiedEmail() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", "google-sub-123");
        attrs.put("email", "author@example.com");
        attrs.put("email_verified", true);

        User existing = new User();
        existing.setId(1L);
        existing.setEmail("author@example.com");
        existing.setRole(Role.AUTHOR);

        when(userIdentityRepository.findByProviderAndProviderUserId("google", "google-sub-123")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(existing));
        when(userIdentityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.resolveLocalUser("google", attrs);

        assertThat(result).isEqualTo(existing);
        verify(userRepository, never()).save(any());

        ArgumentCaptor<UserIdentity> captor = ArgumentCaptor.forClass(UserIdentity.class);
        verify(userIdentityRepository).save(captor.capture());
        assertThat(captor.getValue().getProvider()).isEqualTo("google");
        assertThat(captor.getValue().getProviderUserId()).isEqualTo("google-sub-123");
        assertThat(captor.getValue().getUser()).isEqualTo(existing);
    }

    @Test
    void resolveAutoCreatesNewUserAsAuthorWhenNoMatch() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", "google-sub-999");
        attrs.put("email", "newperson@example.com");
        attrs.put("email_verified", true);
        attrs.put("name", "New Person");

        when(userIdentityRepository.findByProviderAndProviderUserId("google", "google-sub-999")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("newperson@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userIdentityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.resolveLocalUser("google", attrs);

        assertThat(result.getRole()).isEqualTo(Role.AUTHOR);
        assertThat(result.getEmail()).isEqualTo("newperson@example.com");
        assertThat(result.getPasswordHash()).isNull();
        verify(userRepository).save(any());
    }

    @Test
    void resolvePopulatesOrcidIdOnlyForOrcidProvider() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("orcid-identifier", "0000-0002-1825-0097");
        attrs.put("email", "researcher@example.com");
        attrs.put("email_verified", true);

        when(userIdentityRepository.findByProviderAndProviderUserId("orcid", "0000-0002-1825-0097")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("researcher@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userIdentityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.resolveLocalUser("orcid", attrs);

        assertThat(result.getOrcidId()).isEqualTo("0000-0002-1825-0097");
    }

    @Test
    void resolveShortCircuitsOnRepeatLoginByExistingIdentity() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", "google-sub-123");
        attrs.put("email", "author@example.com");
        attrs.put("email_verified", true);

        User existing = new User();
        existing.setId(1L);
        existing.setEmail("author@example.com");
        existing.setRole(Role.AUTHOR);

        UserIdentity existingIdentity = new UserIdentity();
        existingIdentity.setUser(existing);
        existingIdentity.setProvider("google");
        existingIdentity.setProviderUserId("google-sub-123");

        when(userIdentityRepository.findByProviderAndProviderUserId("google", "google-sub-123")).thenReturn(Optional.of(existingIdentity));

        User result = service.resolveLocalUser("google", attrs);

        assertThat(result).isEqualTo(existing);
        verify(userRepository, never()).findByEmail(any());
        verify(userIdentityRepository, never()).save(any());
    }
}
