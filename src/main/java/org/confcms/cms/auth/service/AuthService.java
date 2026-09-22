package org.confcms.cms.auth.service;

import org.confcms.cms.domain.User;
import org.confcms.cms.domain.UserIdentity;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.core.security.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public User registerUser(String email, String password, String fullName, Role role) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already in use");
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFullName(fullName);
        user.setRole(role);
        user.setEnabled(true);

        User saved = userRepository.save(user);

        UserIdentity identity = new UserIdentity();
        identity.setUser(saved);
        identity.setProvider("local");
        identity.setLinkedAt(LocalDateTime.now());
        userIdentityRepository.save(identity);

        return saved;
    }

    @Transactional
    public User createUserDirect(String email, String fullName, Role role) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email already in use");
        }

        User user = new User();
        user.setEmail(email);
        user.setFullName(fullName);
        user.setRole(role);
        user.setEnabled(true);
        // passwordHash intentionally left null -- this user authenticates via
        // Google, ORCID, or magic link first; a "local" UserIdentity is created
        // lazily the same way PasswordResetService.resetPassword already does,
        // the first time they actually set a password.

        return userRepository.save(user);
    }
}
