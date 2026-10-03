package org.confcms.cms.sponsor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SponsorRepository extends JpaRepository<Sponsor, Long> {
    List<Sponsor> findByConferenceIdOrderByDisplayOrderAsc(Long conferenceId);
}
