package org.confcms.cms.conference;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConferenceRepository extends JpaRepository<Conference, Long> {
    Optional<Conference> findByIsActiveTrue();
}
