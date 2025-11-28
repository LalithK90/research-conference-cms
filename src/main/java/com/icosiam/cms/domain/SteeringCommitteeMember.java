package com.icosiam.cms.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "steering_committee")
@Getter
@Setter
public class SteeringCommitteeMember extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String role; // e.g., "Conference Chair"

    @Column(columnDefinition = "TEXT")
    private String bio;

    private String imageUrl;
}
