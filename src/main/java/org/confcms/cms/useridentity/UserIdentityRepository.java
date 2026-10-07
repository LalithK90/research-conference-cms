package org.confcms.cms.useridentity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {
    Optional<UserIdentity> findByProviderAndProviderUserId(String provider, String providerUserId);
    Optional<UserIdentity> findByUserIdAndProvider(Long userId, String provider);
    List<UserIdentity> findByUserId(Long userId);
    boolean existsByUserIdAndProvider(Long userId, String provider);
}
