package org.confcms.cms.sponsor;

import org.confcms.cms.core.domain.BaseEntity;
import org.confcms.cms.domain.Conference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "sponsors")
@Getter
@Setter
public class Sponsor extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String logoUrl;

    private String websiteUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SponsorTier tier = SponsorTier.PARTNER;

    @Column(nullable = false)
    private int displayOrder = 0;
}
