package org.confcms.cms.speaker;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SpeakerRepository extends JpaRepository<Speaker, Long> {
    List<Speaker> findByConferenceIdOrderByDisplayOrderAsc(Long conferenceId);
}
