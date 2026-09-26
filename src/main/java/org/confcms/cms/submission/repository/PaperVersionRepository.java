package org.confcms.cms.submission.repository;

import org.confcms.cms.submission.domain.PaperVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaperVersionRepository extends JpaRepository<PaperVersion, Long> {
    List<PaperVersion> findByContentHash(String contentHash);
}
