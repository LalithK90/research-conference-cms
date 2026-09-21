package org.confcms.cms.repository;

import org.confcms.cms.domain.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {
    Optional<UserIdentity> findByProviderAndProviderUserId(String provider, String providerUserId);
    List<UserIdentity> findByUserId(Long userId);
    boolean existsByUserIdAndProvider(Long userId, String provider);
}
