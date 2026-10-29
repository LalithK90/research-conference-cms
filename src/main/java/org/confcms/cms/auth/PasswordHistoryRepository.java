package org.confcms.cms.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, Long> {

    List<PasswordHistory> findByUserIdOrderByCreatedAtDesc(Long userId);

    // Retention trim: keeps only the rows whose id is in idsToKeep (the N most recent, chosen by
    // the service layer) and deletes everything else for this user.
    void deleteByUserIdAndIdNotIn(Long userId, List<Long> idsToKeep);
}
