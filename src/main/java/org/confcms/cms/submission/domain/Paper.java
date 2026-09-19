package org.confcms.cms.submission.domain;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.core.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "papers")
@Getter
@Setter
public class Paper extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String abstractText;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitter_id", nullable = false)
    private User submitter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaperStatus status = PaperStatus.SUBMITTED;

    @Column(nullable = false)
    private String track; // Theme/Track

    @OneToMany(mappedBy = "paper", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PaperVersion> versions = new ArrayList<>();

    @OneToMany(mappedBy = "paper", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PaperAuthor> authors = new ArrayList<>();
}
