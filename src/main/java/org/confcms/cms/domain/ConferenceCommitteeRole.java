package org.confcms.cms.domain;

import org.confcms.cms.core.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "conference_committee_roles",
       uniqueConstraints = @UniqueConstraint(columnNames = {"conference_id", "user_id", "role"}))
@Getter
@Setter
public class ConferenceCommitteeRole extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommitteeRole role;

    private String displayTitle; // optional public-page label; null -> render role.name()

    @Column(columnDefinition = "TEXT")
    private String bio;

    private String photoUrl;
}
