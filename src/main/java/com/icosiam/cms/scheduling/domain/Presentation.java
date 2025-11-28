package com.icosiam.cms.scheduling.domain;

import com.icosiam.cms.domain.BaseEntity;
import com.icosiam.cms.submission.domain.Paper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "presentations")
@Getter
@Setter
public class Presentation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_id", nullable = false)
    private Paper paper;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
}
