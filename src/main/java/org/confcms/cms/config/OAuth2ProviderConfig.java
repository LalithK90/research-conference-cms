package org.confcms.cms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the OAuth2 {@link ClientRegistrationRepository} from whichever provider credentials are
 * actually configured. Each provider (Google, ORCID) is registered only when both its
 * client-id and client-secret properties are set, so an installation with neither configured
 * gets no OAuth2 login options at all.
 */
@Configuration
public class OAuth2ProviderConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${spring.security.oauth2.client.registration.google.client-id:}") String googleClientId,
            @Value("${spring.security.oauth2.client.registration.google.client-secret:}") String googleClientSecret,
            @Value("${spring.security.oauth2.client.registration.orcid.client-id:}") String orcidClientId,
            @Value("${spring.security.oauth2.client.registration.orcid.client-secret:}") String orcidClientSecret) {

        List<ClientRegistration> registrations = new ArrayList<>();

        if (!googleClientId.isBlank() && !googleClientSecret.isBlank()) {
            registrations.add(ClientRegistration.withRegistrationId("google")
                    .clientId(googleClientId)
                    .clientSecret(googleClientSecret)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid", "profile", "email")
                    .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                    .tokenUri("https://www.googleapis.com/oauth2/v4/token")
                    .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
                    .userNameAttributeName("sub")
                    .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                    .clientName("Google")
                    .build());
        }

        if (!orcidClientId.isBlank() && !orcidClientSecret.isBlank()) {
            // No userInfoUri: ORCID's public API only exposes per-user profile data at
            // /v3.0/{orcid}/person, a path templated on the user's own ORCID iD -- a value
            // that isn't known until after the token exchange, which Spring's default
            // DefaultOAuth2UserService can't resolve (it expects a fixed userInfoUri and
            // performs the userinfo call unconditionally). ORCID's token response already
            // includes the "orcid" and "name" fields directly, so CustomOAuth2UserService
            // reads them from OAuth2UserRequest#getAdditionalParameters() instead of making
            // a second HTTP call.
            registrations.add(ClientRegistration.withRegistrationId("orcid")
                    .clientId(orcidClientId)
                    .clientSecret(orcidClientSecret)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("/authenticate")
                    .authorizationUri("https://orcid.org/oauth/authorize")
                    .tokenUri("https://orcid.org/oauth/token")
                    .userNameAttributeName("orcid")
                    .clientName("ORCID")
                    .build());
        }

        // InMemoryClientRegistrationRepository's List constructor calls
        // Assert.notEmpty(registrations, "registrations cannot be empty") and throws
        // IllegalArgumentException for an empty list (confirmed by decompiling
        // spring-security-oauth2-client 7.1.0's createRegistrationsMap). With no provider
        // configured -- the common case for a fresh installation -- registrations is empty,
        // so an empty-safe repository is returned instead of forcing a placeholder
        // registration into existence.
        if (registrations.isEmpty()) {
            return registrationId -> null;
        }
        return new InMemoryClientRegistrationRepository(registrations);
    }
}
