package com.icosiam.cms.scheduling.repository;

import com.icosiam.cms.scheduling.domain.Session;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SessionRepository extends JpaRepository<Session, Long> {
    List<Session> findByConferenceId(Long conferenceId);
    List<Session> findByConferenceIdOrderByStartTimeAsc(Long conferenceId);
}
