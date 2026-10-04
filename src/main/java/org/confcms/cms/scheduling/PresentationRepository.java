package org.confcms.cms.scheduling;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PresentationRepository extends JpaRepository<Presentation, Long> {
    List<Presentation> findBySessionId(Long sessionId);
    Optional<Presentation> findByPaperId(Long paperId);
}
