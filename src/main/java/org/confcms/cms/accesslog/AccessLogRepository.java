package org.confcms.cms.accesslog;

import org.confcms.cms.accesslog.AccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {
    List<AccessLog> findTop100ByOrderByCreatedAtDesc();
}
