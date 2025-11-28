package com.icosiam.cms.repository;

import com.icosiam.cms.domain.User;
import com.icosiam.cms.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    java.util.List<User> findByRole(Role role);
    Optional<User> findByProviderAndProviderId(String provider, String providerId);
}
