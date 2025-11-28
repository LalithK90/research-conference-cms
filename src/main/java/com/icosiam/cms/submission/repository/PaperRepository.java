package com.icosiam.cms.submission.repository;

import com.icosiam.cms.submission.domain.Paper;
import com.icosiam.cms.submission.domain.PaperStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaperRepository extends JpaRepository<Paper, Long> {
    List<Paper> findBySubmitterId(Long submitterId);
    List<Paper> findByStatus(PaperStatus status);
}
