package org.confcms.cms.repository;

import org.confcms.cms.domain.CommitteeRole;
import org.confcms.cms.domain.ConferenceCommitteeRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConferenceCommitteeRoleRepository extends JpaRepository<ConferenceCommitteeRole, Long> {
    List<ConferenceCommitteeRole> findByConferenceId(Long conferenceId);
    Optional<ConferenceCommitteeRole> findByConferenceIdAndUserIdAndRole(Long conferenceId, Long userId, CommitteeRole role);
    Optional<ConferenceCommitteeRole> findByConferenceIdAndRole(Long conferenceId, CommitteeRole role);
}
