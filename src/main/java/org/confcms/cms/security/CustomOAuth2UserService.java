package org.confcms.cms.security;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.domain.UserIdentity;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        String provider = userRequest.getClientRegistration().getRegistrationId();
        Map<String, Object> attributes = "orcid".equals(provider)
                ? orcidAttributesFromTokenResponse(userRequest)
                : super.loadUser(userRequest).getAttributes();

        User localUser = resolveLocalUser(provider, attributes);

        GrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + localUser.getRole().name());
        return new DefaultOAuth2User(Collections.singletonList(authority), attributes, emailAttributeKey(provider));
    }

    /**
     * ORCID has no fixed userinfo endpoint: its public per-user profile data lives at
     * /v3.0/{orcid}/person, a path templated on the user's own ORCID iD, which isn't known
     * until after the token exchange -- Spring's DefaultOAuth2UserService only supports a
     * fixed userInfoUri and would call it unconditionally, so it can't be used here (confirmed
     * against ORCID's OAuth2 documentation: token endpoint tutorial at
     * https://info.orcid.org/documentation/api-tutorials/api-tutorial-get-and-authenticated-orcid-id/).
     * ORCID's token response already includes the "orcid" and "name" fields directly, so they're
     * read from the additional parameters Spring preserves on OAuth2UserRequest, avoiding a
     * second HTTP call entirely. ORCID's basic OAuth2 grant does not return an email address at
     * all (Member API email scope is a separate, more privileged grant this integration does not
     * request), so no "email"/"email_verified" keys are present for this provider.
     */
    private Map<String, Object> orcidAttributesFromTokenResponse(OAuth2UserRequest userRequest) {
        Map<String, Object> additionalParameters = userRequest.getAdditionalParameters();
        Map<String, Object> attributes = new java.util.HashMap<>();
        attributes.put("orcid-identifier", additionalParameters.get("orcid"));
        attributes.put("name", additionalParameters.get("name"));
        return attributes;
    }

    @Transactional
    public User resolveLocalUser(String provider, Map<String, Object> attributes) {
        String providerUserId = providerUserId(provider, attributes);

        Optional<UserIdentity> existingIdentity = userIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId);
        if (existingIdentity.isPresent()) {
            return existingIdentity.get().getUser();
        }

        // ORCID's basic /authenticate grant never supplies an email, so there is nothing to
        // auto-link or auto-create a User by/from (User.email is NOT NULL UNIQUE). A first-time
        // ORCID login must instead be linked explicitly from an existing account.
        if ("orcid".equals(provider)) {
            throw new OAuth2AuthenticationException(new OAuth2Error("orcid_not_linked"),
                    "ORCID login requires an existing account. Log in with your password or Google account first, then link ORCID from your account settings.");
        }

        Boolean verified = (Boolean) attributes.get("email_verified");
        if (verified == null || !verified) {
            throw new OAuth2AuthenticationException(new OAuth2Error("email_not_verified"),
                    "Provider did not return a verified email address");
        }

        String email = (String) attributes.get("email");

        User user = userRepository.findByEmail(email).orElseGet(() -> {
            User created = new User();
            created.setEmail(email);
            created.setFullName((String) attributes.getOrDefault("name", email));
            created.setRole(Role.AUTHOR);
            created.setEnabled(true);
            return userRepository.save(created);
        });

        UserIdentity identity = new UserIdentity();
        identity.setUser(user);
        identity.setProvider(provider);
        identity.setProviderUserId(providerUserId);
        identity.setLinkedAt(LocalDateTime.now());
        userIdentityRepository.save(identity);

        return user;
    }

    private String providerUserId(String provider, Map<String, Object> attributes) {
        if ("orcid".equals(provider)) {
            return (String) attributes.get("orcid-identifier");
        }
        return (String) attributes.get("sub");
    }

    // DefaultOAuth2User#getName() resolves to attributes.get(nameAttributeKey) and requires the
    // key to be present (Assert.notNull) -- so this can't just be "email" for every provider.
    // Google's userinfo response does include "email", so "email" is a reasonable (if
    // semantically odd -- "sub" would be the conventional choice) key to use as the principal
    // name there. ORCID's attributes never contain "email" (see orcidAttributesFromTokenResponse)
    // -- using "email" here would throw IllegalArgumentException on every ORCID login. ORCID's
    // attributes always contain "orcid-identifier", so that's used as its name key instead.
    private String emailAttributeKey(String provider) {
        if ("orcid".equals(provider)) {
            return "orcid-identifier";
        }
        return "email";
    }
}
