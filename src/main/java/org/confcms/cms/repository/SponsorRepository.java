package org.confcms.cms.repository;

import org.confcms.cms.domain.Sponsor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SponsorRepository extends JpaRepository<Sponsor, Long> {
    List<Sponsor> findByConferenceIdOrderByDisplayOrderAsc(Long conferenceId);
}
