package org.confcms.cms.accesslog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {
    List<AccessLog> findTop100ByOrderByCreatedAtDesc();

    // Scoped by (email, requester IP), not email alone: limiting by email alone lets anyone
    // who knows a victim's email lock the real victim out of their own magic-link requests
    // for the rest of the window just by submitting that email first from a different IP.
    Optional<AccessLog> findTopByEventTypeAndRequestedEmailIgnoreCaseAndIpAddressOrderByCreatedAtDesc(
            AccessEventType eventType, String requestedEmail, String ipAddress);
}
