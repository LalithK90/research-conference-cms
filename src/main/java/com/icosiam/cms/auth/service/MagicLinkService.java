package com.icosiam.cms.auth.service;

import com.icosiam.cms.repository.UserRepository;
import com.icosiam.cms.domain.MagicLink;
import com.icosiam.cms.repository.MagicLinkRepository;
import com.icosiam.cms.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MagicLinkService {

    private final MagicLinkRepository magicLinkRepository;
    private final UserRepository userRepository;

    @Transactional
    public MagicLink createMagicLinkForEmail(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("No user found for email"));

        MagicLink link = new MagicLink();
        link.setUser(user);
        link.setToken(UUID.randomUUID().toString());
        link.setExpiresAt(LocalDateTime.now().plusHours(2));
        link.setUsed(false);

        return magicLinkRepository.save(link);
    }

    @Transactional(readOnly = true)
    public Optional<MagicLink> findByToken(String token) {
        return magicLinkRepository.findByToken(token);
    }

    @Transactional
    public void markUsed(MagicLink link) {
        link.setUsed(true);
        magicLinkRepository.save(link);
    }
}
