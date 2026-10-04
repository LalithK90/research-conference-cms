package org.confcms.cms.conference;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConferenceCommitteeRoleRepository extends JpaRepository<ConferenceCommitteeRole, Long> {
    List<ConferenceCommitteeRole> findByConferenceId(Long conferenceId);
    Optional<ConferenceCommitteeRole> findByConferenceIdAndUserIdAndRole(Long conferenceId, Long userId, CommitteeRole role);
    Optional<ConferenceCommitteeRole> findByConferenceIdAndRole(Long conferenceId, CommitteeRole role);
    boolean existsByConferenceIdAndUserId(Long conferenceId, Long userId);

    // One query for "every conference this user chairs or co-chairs", used to avoid an N+1
    // when a caller needs to check chair-or-co-chair status against a whole list of papers
    // (each potentially from a different conference) rather than one conference at a time.
    @Query("SELECT ccr.conference.id FROM ConferenceCommitteeRole ccr "
            + "WHERE ccr.user.id = :userId AND ccr.role IN ('CHAIR', 'CO_CHAIR')")
    List<Long> findChairOrCoChairConferenceIds(@Param("userId") Long userId);
}
