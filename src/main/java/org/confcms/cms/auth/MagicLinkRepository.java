package org.confcms.cms.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MagicLinkRepository extends JpaRepository<MagicLink, Long> {
    Optional<MagicLink> findByToken(String token);

    // Conditional on used = false so two concurrent requests racing on the same token can't
    // both succeed: only the request whose UPDATE actually flips a row gets a non-zero count.
    @Modifying
    @Query("UPDATE MagicLink m SET m.used = true WHERE m.id = :id AND m.used = false")
    int markUsedIfUnused(Long id);
}
