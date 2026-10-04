package org.confcms.cms.accesslog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {
    List<AccessLog> findTop100ByOrderByCreatedAtDesc();

    Optional<AccessLog> findTopByEventTypeAndRequestedEmailIgnoreCaseOrderByCreatedAtDesc(
            AccessEventType eventType, String requestedEmail);
}
