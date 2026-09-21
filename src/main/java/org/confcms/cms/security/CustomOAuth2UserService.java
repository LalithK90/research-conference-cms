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
        OAuth2User oauth2User = super.loadUser(userRequest);
        String provider = userRequest.getClientRegistration().getRegistrationId();

        User localUser = resolveLocalUser(provider, oauth2User.getAttributes());

        GrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + localUser.getRole().name());
        return new DefaultOAuth2User(Collections.singletonList(authority), oauth2User.getAttributes(), emailAttributeKey(provider));
    }

    @Transactional
    public User resolveLocalUser(String provider, Map<String, Object> attributes) {
        Boolean verified = (Boolean) attributes.get("email_verified");
        if (verified == null || !verified) {
            throw new OAuth2AuthenticationException(new OAuth2Error("email_not_verified"),
                    "Provider did not return a verified email address");
        }

        String providerUserId = providerUserId(provider, attributes);
        String email = (String) attributes.get("email");

        Optional<UserIdentity> existingIdentity = userIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId);
        if (existingIdentity.isPresent()) {
            return existingIdentity.get().getUser();
        }

        User user = userRepository.findByEmail(email).orElseGet(() -> {
            User created = new User();
            created.setEmail(email);
            created.setFullName((String) attributes.getOrDefault("name", email));
            created.setRole(Role.AUTHOR);
            created.setEnabled(true);
            return userRepository.save(created);
        });

        if ("orcid".equals(provider)) {
            user.setOrcidId(providerUserId);
            userRepository.save(user);
        }

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

    private String emailAttributeKey(String provider) {
        return "email";
    }
}
