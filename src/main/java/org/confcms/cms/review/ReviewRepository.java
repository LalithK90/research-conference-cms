package org.confcms.cms.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {
    List<Review> findByPaperId(Long paperId);
    List<Review> findByReviewerId(Long reviewerId);
}
