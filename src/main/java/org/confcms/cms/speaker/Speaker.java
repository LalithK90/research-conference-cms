package org.confcms.cms.speaker;

import org.confcms.cms.core.domain.BaseEntity;
import org.confcms.cms.conference.Conference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "speakers")
@Getter
@Setter
public class Speaker extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @Column(nullable = false)
    private String fullName;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String bio;

    private String photoUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SpeakerType type = SpeakerType.KEYNOTE;

    @Column(nullable = false)
    private int displayOrder = 0;
}
