package org.confcms.cms.auth;

import lombok.RequiredArgsConstructor;
import org.confcms.cms.user.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PasswordHistoryService {

    // Keep the last 5 passwords per user -- new password resets are checked against this many
    // past hashes, and older rows beyond this count are trimmed on every record().
    private static final int MAX_HISTORY = 5;

    private final PasswordHistoryRepository repository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public boolean isReuse(User user, String rawPassword) {
        return repository.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .anyMatch(history -> passwordEncoder.matches(rawPassword, history.getPasswordHash()));
    }

    @Transactional
    public void record(User user, String newPasswordHash) {
        List<PasswordHistory> existing = repository.findByUserIdOrderByCreatedAtDesc(user.getId());

        PasswordHistory entry = new PasswordHistory();
        entry.setUser(user);
        entry.setPasswordHash(newPasswordHash);
        PasswordHistory saved = repository.save(entry);

        if (existing.size() + 1 > MAX_HISTORY) {
            List<Long> idsToKeep = existing.stream()
                    .limit(MAX_HISTORY - 1L)
                    .map(PasswordHistory::getId)
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            idsToKeep.add(saved.getId());
            repository.deleteByUserIdAndIdNotIn(user.getId(), idsToKeep);
        }
    }
}
