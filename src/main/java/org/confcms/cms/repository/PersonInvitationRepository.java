package org.confcms.cms.repository;

import org.confcms.cms.domain.PersonInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PersonInvitationRepository extends JpaRepository<PersonInvitation, Long> {
    Optional<PersonInvitation> findByToken(String token);
    List<PersonInvitation> findByConferenceId(Long conferenceId);
}
