package org.confcms.cms.review.repository;

import org.confcms.cms.review.domain.ReviewAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewAssignmentRepository extends JpaRepository<ReviewAssignment, Long> {
    List<ReviewAssignment> findByPaperId(Long paperId);
    List<ReviewAssignment> findByReviewerId(Long reviewerId);
}
