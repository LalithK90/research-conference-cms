package org.confcms.cms.security;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.domain.UserIdentity;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private static final String NAME_ATTRIBUTE_KEY = "email";

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        String provider = userRequest.getClientRegistration().getRegistrationId();
        Map<String, Object> attributes = "orcid".equals(provider)
                ? orcidAttributesFromTokenResponse(userRequest)
                : super.loadUser(userRequest).getAttributes();

        User localUser = resolveLocalUser(provider, attributes);

        // The principal name must always be an email: every other authenticated call site in
        // this codebase (controllers, REST endpoints) does
        // SecurityContextHolder...getAuthentication().getName() and feeds the result straight
        // into userRepository.findByEmail(...). Google's attributes already contain "email", but
        // ORCID's never do (see orcidAttributesFromTokenResponse) -- so a fresh map carrying the
        // resolved local user's real email is used unconditionally for both providers, instead of
        // branching per-provider on whatever attribute the OAuth2 provider happens to supply.
        Map<String, Object> principalAttributes = new HashMap<>(attributes);
        principalAttributes.put(NAME_ATTRIBUTE_KEY, localUser.getEmail());

        GrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + localUser.getRole().name());
        return new DefaultOAuth2User(Collections.singletonList(authority), principalAttributes, NAME_ATTRIBUTE_KEY);
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

        // An already-logged-in user clicking "Connect Google"/"Connect ORCID" from /account
        // re-enters this exact same OAuth2 flow. That request carries a real authenticated
        // session, so the new UserIdentity must link to THAT session's user -- not to whichever
        // account happens to share the provider-supplied email (Google), and not be rejected
        // outright for lacking one (ORCID). This is checked before either the ORCID rejection or
        // the Google by-email auto-link/auto-create logic, since it applies identically to both
        // providers and takes priority over them.
        User currentSessionUser = currentAuthenticatedUser();
        if (currentSessionUser != null) {
            UserIdentity identity = new UserIdentity();
            identity.setUser(currentSessionUser);
            identity.setProvider(provider);
            identity.setProviderUserId(providerUserId);
            identity.setLinkedAt(LocalDateTime.now());
            userIdentityRepository.save(identity);
            return currentSessionUser;
        }

        // ORCID's basic /authenticate grant never supplies an email, so there is nothing to
        // auto-link or auto-create a User by/from (User.email is NOT NULL UNIQUE). A first-time,
        // cold ORCID login (no existing session) must instead be linked explicitly from an
        // existing account (handled by the already-authenticated branch above).
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

    // Mirrors the "who is the acting user" check every controller in this codebase performs via
    // SecurityContextHolder...getAuthentication().getName() + userRepository.findByEmail(...).
    // Here the intent is different: rather than requiring an authenticated user (those call
    // sites all run behind an authenticated route), this needs to distinguish "there IS a real,
    // already-authenticated session" (an authenticated user clicked Connect Google/ORCID from
    // /account) from "there is no session at all" (a cold OAuth2 login), returning null for the
    // latter so the existing cold-login logic below is unaffected. AnonymousAuthenticationToken
    // is what Spring Security's anonymousAuthenticationFilter installs for unauthenticated
    // requests by default, so it must be excluded explicitly -- Authentication#isAuthenticated()
    // alone returns true for anonymous tokens too.
    private User currentAuthenticatedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return userRepository.findByEmail(authentication.getName()).orElse(null);
    }
}
