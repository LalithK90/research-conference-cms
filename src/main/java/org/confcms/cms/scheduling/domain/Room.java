package org.confcms.cms.scheduling.domain;

import org.confcms.cms.core.domain.BaseEntity;
import org.confcms.cms.domain.Conference;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "rooms")
@Getter
@Setter
public class Room extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private Integer capacity;
    private String location; // e.g., "Building A, 2nd Floor"

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;
}
