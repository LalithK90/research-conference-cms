package org.confcms.cms.auth;

import org.confcms.cms.user.UserRepository;
import org.confcms.cms.user.User;
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

    // Returns false if another request already marked this link used first (e.g. two
    // concurrent requests racing on the same token) -- the caller must treat that as a failed
    // authentication rather than letting both requests succeed.
    @Transactional
    public boolean markUsed(MagicLink link) {
        int updated = magicLinkRepository.markUsedIfUnused(link.getId());
        if (updated > 0) {
            link.setUsed(true);
            return true;
        }
        return false;
    }
}
