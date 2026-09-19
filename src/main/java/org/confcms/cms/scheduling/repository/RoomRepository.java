package org.confcms.cms.scheduling.repository;

import org.confcms.cms.scheduling.domain.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {
    List<Room> findByConferenceId(Long conferenceId);
}
