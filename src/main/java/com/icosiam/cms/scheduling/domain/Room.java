package com.icosiam.cms.scheduling.domain;

import com.icosiam.cms.domain.BaseEntity;
import com.icosiam.cms.domain.Conference;
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
