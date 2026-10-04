package org.confcms.cms.review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewDeclineRepository extends JpaRepository<ReviewDecline, Long> {
}
