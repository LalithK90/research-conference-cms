package org.confcms.cms.paper;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaperRepository extends JpaRepository<Paper, Long> {
    List<Paper> findBySubmitterId(Long submitterId);
    List<Paper> findByStatus(PaperStatus status);
    List<Paper> findByConferenceId(Long conferenceId);
    List<Paper> findByConferenceIdAndStatus(Long conferenceId, PaperStatus status);

    // Serializes concurrent assignPaperToSession calls for the SAME paper (see
    // SchedulingService.assignPaperToSession), narrowing the already-assigned check-then-insert
    // race to the width of this transaction -- same pattern as UserRepository.findByIdForUpdate.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Paper p WHERE p.id = :id")
    Optional<Paper> findByIdForUpdate(Long id);
}
