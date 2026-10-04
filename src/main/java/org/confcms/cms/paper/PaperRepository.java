package org.confcms.cms.paper;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaperRepository extends JpaRepository<Paper, Long> {
    List<Paper> findBySubmitterId(Long submitterId);
    List<Paper> findByStatus(PaperStatus status);
    List<Paper> findByConferenceIdAndStatus(Long conferenceId, PaperStatus status);
}
