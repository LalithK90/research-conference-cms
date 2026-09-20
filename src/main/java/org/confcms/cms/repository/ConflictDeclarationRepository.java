package org.confcms.cms.repository;

import org.confcms.cms.domain.ConflictDeclaration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConflictDeclarationRepository extends JpaRepository<ConflictDeclaration, Long> {
    List<ConflictDeclaration> findByConferenceIdAndReviewerId(Long conferenceId, Long reviewerId);
    Optional<ConflictDeclaration> findByConferenceIdAndReviewerIdAndDeclaredAgainstUserId(Long conferenceId, Long reviewerId, Long declaredAgainstUserId);
    boolean existsByConferenceIdAndReviewerIdAndDeclaredAgainstUserId(Long conferenceId, Long reviewerId, Long declaredAgainstUserId);
}
