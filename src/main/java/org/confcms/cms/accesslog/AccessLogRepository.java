package org.confcms.cms.accesslog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    // Deliberately NOT scoped by IP: this is the second, looser ceiling that caps total real
    // sends to one email address across all requesting IPs combined, closing the gap the
    // per-(email, IP) check above leaves open against an attacker rotating through multiple
    // IPs (a botnet or proxy chain) to email-bomb a single victim address.
    long countByEventTypeAndRequestedEmailIgnoreCaseAndEmailSentTrueAndCreatedAtAfter(
            AccessEventType eventType, String requestedEmail, LocalDateTime after);
}
