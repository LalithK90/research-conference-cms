package org.confcms.cms.auth;

import org.confcms.cms.user.User;
import org.confcms.cms.useridentity.UserIdentity;
import org.confcms.cms.useridentity.UserIdentityRepository;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.core.security.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AuthService {

    // Deliberately permissive (not RFC 5322): this only needs to reject obviously
    // malformed input before it becomes an unreachable account, not fully validate
    // email syntax -- the real check is whether the address actually receives mail.
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

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

    // Refuses to change the role or enabled-state of the LAST enabled admin -- without this,
    // an admin could demote or disable themselves (or the only other admin) and leave the
    // installation with no one able to reach any /admin/** route, a state nothing in this app
    // can recover from short of direct database access.
    private void requireNotLastEnabledAdmin(User user) {
        if (user.getRole() != Role.ADMIN || !user.isEnabled()) {
            return;
        }
        long otherEnabledAdmins = userRepository.findByRole(Role.ADMIN).stream()
                .filter(User::isEnabled)
                .filter(u -> !u.getId().equals(user.getId()))
                .count();
        if (otherEnabledAdmins == 0) {
            throw new IllegalStateException("Can't change the last enabled admin account.");
        }
    }

    @Transactional
    public User updateRole(Long userId, Role newRole) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (newRole != user.getRole()) {
            requireNotLastEnabledAdmin(user);
        }
        user.setRole(newRole);
        return userRepository.save(user);
    }

    @Transactional
    public User setEnabled(Long userId, boolean enabled) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        if (!enabled) {
            requireNotLastEnabledAdmin(user);
        }
        user.setEnabled(enabled);
        return userRepository.save(user);
    }

    @Transactional
    public User createUserDirect(String email, String fullName, Role role) {
        // This user has no password to fall back on -- their only path in is an
        // exact-match lookup by this email (Google/ORCID/magic-link auto-link), so
        // a malformed address here creates an account nobody can ever log into.
        if (email == null || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("A valid email address is required");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("Full name is required");
        }
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
