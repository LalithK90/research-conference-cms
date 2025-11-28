package com.icosiam.cms.review.repository;

import com.icosiam.cms.review.domain.ReviewBid;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewBidRepository extends JpaRepository<ReviewBid, Long> {
    List<ReviewBid> findByPaperId(Long paperId);
    List<ReviewBid> findByReviewerId(Long reviewerId);
    Optional<ReviewBid> findByReviewerIdAndPaperId(Long reviewerId, Long paperId);
}
