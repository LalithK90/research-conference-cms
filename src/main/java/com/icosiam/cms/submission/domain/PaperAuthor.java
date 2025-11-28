package com.icosiam.cms.submission.domain;

import com.icosiam.cms.core.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "paper_authors")
@Getter
@Setter
public class PaperAuthor extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_id", nullable = false)
    private Paper paper;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String affiliation;

    @Column(nullable = false)
    private boolean isPresenter;
}
