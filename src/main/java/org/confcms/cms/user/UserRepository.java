package org.confcms.cms.user;

import org.confcms.cms.core.security.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    java.util.List<User> findByRole(Role role);

    // Serializes concurrent registration attempts by the SAME user (double-submit, two browser
    // tabs) without locking other users' registrations against each other -- see
    // RegistrationService.register(). Narrows the check-then-insert race to the width of this
    // transaction instead of closing it with a DB constraint, since there's no migration tool
    // wired up to add one (schema is Hibernate ddl-auto=update only).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(Long id);
}
