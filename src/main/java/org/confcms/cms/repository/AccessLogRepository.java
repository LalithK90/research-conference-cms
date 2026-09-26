package org.confcms.cms.repository;

import org.confcms.cms.domain.AccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {
    List<AccessLog> findTop100ByOrderByCreatedAtDesc();
}
