package org.confcms.cms.security;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.domain.UserIdentity;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.time.Instant;
import java.util.Collections;
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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
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

    private ClientRegistration orcidRegistration() {
        return ClientRegistration.withRegistrationId("orcid")
                .clientId("test-client")
                .clientSecret("test-secret")
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://orcid.org/oauth/authorize")
                .tokenUri("https://orcid.org/oauth/token")
                .userNameAttributeName("orcid-identifier")
                .clientName("ORCID")
                .build();
    }

    private void authenticateAs(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "n/a",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_AUTHOR"))));
    }

    @Test
    void resolveRejectsUnverifiedEmail() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", "google-sub-123");
        attrs.put("email", "author@example.com");
        attrs.put("email_verified", false);

        when(userIdentityRepository.findByProviderAndProviderUserId("google", "google-sub-123")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveLocalUser("google", attrs))
                .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);

        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void resolveRejectsFirstTimeOrcidLoginWithNoExistingIdentity() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("orcid-identifier", "0000-0002-1825-0097");
        attrs.put("name", "New Researcher");

        when(userIdentityRepository.findByProviderAndProviderUserId("orcid", "0000-0002-1825-0097")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveLocalUser("orcid", attrs))
                .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void resolveReturningOrcidLoginSucceedsWithoutEmailAttributes() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("orcid-identifier", "0000-0002-1825-0097");
        attrs.put("name", "Returning Researcher");

        User existing = new User();
        existing.setId(1L);
        existing.setEmail("researcher@example.com");
        existing.setRole(Role.AUTHOR);

        UserIdentity existingIdentity = new UserIdentity();
        existingIdentity.setUser(existing);
        existingIdentity.setProvider("orcid");
        existingIdentity.setProviderUserId("0000-0002-1825-0097");

        when(userIdentityRepository.findByProviderAndProviderUserId("orcid", "0000-0002-1825-0097")).thenReturn(Optional.of(existingIdentity));

        User result = service.resolveLocalUser("orcid", attrs);

        assertThat(result).isEqualTo(existing);
        verify(userRepository, never()).findByEmail(any());
        verify(userIdentityRepository, never()).save(any());
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

    // Formerly resolvePopulatesOrcidIdOnlyForOrcidProvider: asserted that a first-time ORCID
    // login (no existing UserIdentity) auto-creates a User and populates orcidId. That behavior
    // is superseded by the ORCID-no-email fix: a first-time ORCID login is now rejected instead
    // (see resolveRejectsFirstTimeOrcidLoginWithNoExistingIdentity above), since ORCID never
    // supplies an email to auto-create a User from. This test is intentionally removed rather
    // than kept, as its assertion directly contradicts the new required behavior.

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

    // --- Fix 1-3: session-aware account linking ---

    @Test
    void resolveLinksOrcidToCurrentSessionUserWhenAlreadyAuthenticated() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("orcid-identifier", "0000-0002-1825-0097");
        attrs.put("name", "Existing Author");

        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setEmail("author@example.com");
        currentUser.setRole(Role.AUTHOR);

        when(userIdentityRepository.findByProviderAndProviderUserId("orcid", "0000-0002-1825-0097")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(currentUser));
        when(userIdentityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        authenticateAs("author@example.com");

        // Before the fix, this call unconditionally threw OAuth2AuthenticationException for
        // ORCID regardless of session state -- an already-logged-in user clicking "Connect
        // ORCID" from /account got rejected with the very message telling them to do what
        // they just did. This must now succeed and link to the current session's user.
        User result = service.resolveLocalUser("orcid", attrs);

        assertThat(result).isEqualTo(currentUser);

        ArgumentCaptor<UserIdentity> captor = ArgumentCaptor.forClass(UserIdentity.class);
        verify(userIdentityRepository).save(captor.capture());
        assertThat(captor.getValue().getProvider()).isEqualTo("orcid");
        assertThat(captor.getValue().getProviderUserId()).isEqualTo("0000-0002-1825-0097");
        assertThat(captor.getValue().getUser()).isEqualTo(currentUser);
    }

    @Test
    void resolveLinksGoogleToCurrentSessionUserNotToGoogleEmailsUser() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", "google-sub-999");
        attrs.put("email", "different-google-email@example.com");
        attrs.put("email_verified", true);

        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setEmail("author@example.com");
        currentUser.setRole(Role.AUTHOR);

        when(userIdentityRepository.findByProviderAndProviderUserId("google", "google-sub-999")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(currentUser));
        when(userIdentityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        authenticateAs("author@example.com");

        // Before the fix, this matched by the GOOGLE ACCOUNT's email ("different-google-email@
        // example.com"), not the current session's user -- silently linking to (or creating) a
        // different User and switching the session's effective identity mid-flow. It must link
        // to the current session's user instead, and never touch userRepository.findByEmail with
        // the Google-supplied email.
        User result = service.resolveLocalUser("google", attrs);

        assertThat(result).isEqualTo(currentUser);
        verify(userRepository, never()).findByEmail("different-google-email@example.com");

        ArgumentCaptor<UserIdentity> captor = ArgumentCaptor.forClass(UserIdentity.class);
        verify(userIdentityRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(currentUser);
        assertThat(captor.getValue().getProviderUserId()).isEqualTo("google-sub-999");
    }

    @Test
    void resolveColdOrcidLoginStillRejectedWithNoSession() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("orcid-identifier", "0000-0002-1825-0097");
        attrs.put("name", "New Researcher");

        when(userIdentityRepository.findByProviderAndProviderUserId("orcid", "0000-0002-1825-0097")).thenReturn(Optional.empty());
        // No authentication set on SecurityContextHolder -- a genuinely cold OAuth2 attempt.

        assertThatThrownBy(() -> service.resolveLocalUser("orcid", attrs))
                .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void resolveColdOrcidLoginStillRejectedWithAnonymousAuthentication() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("orcid-identifier", "0000-0002-1825-0097");
        attrs.put("name", "New Researcher");

        when(userIdentityRepository.findByProviderAndProviderUserId("orcid", "0000-0002-1825-0097")).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThatThrownBy(() -> service.resolveLocalUser("orcid", attrs))
                .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void loadUserForOrcidReturnsResolvedUsersEmailAsPrincipalName() {
        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setEmail("author@example.com");
        currentUser.setRole(Role.AUTHOR);

        when(userIdentityRepository.findByProviderAndProviderUserId("orcid", "0000-0002-1825-0097")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(currentUser));
        when(userIdentityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        authenticateAs("author@example.com");

        OAuth2AccessToken accessToken = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
                "test-token", Instant.now(), Instant.now().plusSeconds(3600));
        Map<String, Object> additionalParameters = new HashMap<>();
        additionalParameters.put("orcid", "0000-0002-1825-0097");
        additionalParameters.put("name", "Existing Author");
        OAuth2UserRequest userRequest = new OAuth2UserRequest(orcidRegistration(), accessToken, additionalParameters);

        // Before the fix, DefaultOAuth2User was built with "orcid-identifier" as the
        // name-attribute key, so authentication.getName() for an ORCID session returned the raw
        // ORCID iD instead of an email -- breaking every call site in the app that does
        // SecurityContextHolder...getAuthentication().getName() and feeds it to
        // userRepository.findByEmail(...). It must now return the resolved user's real email.
        OAuth2User result = service.loadUser(userRequest);

        assertThat(result.getName()).isEqualTo("author@example.com");
        assertThat(result.getAttributes()).containsEntry("email", "author@example.com");
    }
}
