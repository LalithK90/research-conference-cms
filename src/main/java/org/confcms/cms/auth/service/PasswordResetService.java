package org.confcms.cms.auth.service;

import org.confcms.cms.domain.PasswordResetToken;
import org.confcms.cms.domain.User;
import org.confcms.cms.domain.UserIdentity;
import org.confcms.cms.repository.PasswordResetTokenRepository;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Duration TOKEN_VALIDITY = Duration.ofHours(2);
    private static final Duration REQUEST_COOLDOWN = Duration.ofMinutes(5);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public void requestReset(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return; // enumeration-safe: silent no-op, same as the "success" path from the caller's perspective
        }
        User user = userOpt.get();

        Optional<PasswordResetToken> recent = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId());
        if (recent.isPresent() && !recent.get().isUsed()
                && recent.get().getExpiresAt().isAfter(LocalDateTime.now().plus(TOKEN_VALIDITY).minus(REQUEST_COOLDOWN))) {
            return; // within cooldown window of the most recent still-valid token; silent no-op
        }

        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(LocalDateTime.now().plus(TOKEN_VALIDITY));
        token.setUsed(false);
        tokenRepository.save(token);

        String link = "/auth/reset-password?token=" + token.getToken();
        emailService.sendSimpleEmail(user.getEmail(), "Password reset requested",
                "Click the link below to reset your password (valid for 2 hours):\n\n" + link
                        + "\n\nIf you did not request this, you can safely ignore this email.");
    }

    @Transactional(readOnly = true)
    public PasswordResetToken validateToken(String tokenValue) {
        PasswordResetToken token = tokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new IllegalArgumentException("This link is invalid, expired, or already used"));

        if (token.isUsed() || token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("This link is invalid, expired, or already used");
        }

        return token;
    }

    @Transactional
    public void resetPassword(String tokenValue, String newPassword) {
        PasswordResetToken token = validateToken(tokenValue);
        User user = token.getUser();

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsed(true);
        tokenRepository.save(token);

        if (!userIdentityRepository.existsByUserIdAndProvider(user.getId(), "local")) {
            UserIdentity identity = new UserIdentity();
            identity.setUser(user);
            identity.setProvider("local");
            identity.setLinkedAt(LocalDateTime.now());
            userIdentityRepository.save(identity);
        }
    }
}
