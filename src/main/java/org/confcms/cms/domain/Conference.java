package org.confcms.cms.domain;

import org.confcms.cms.core.domain.BaseEntity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "conferences")
@Getter
@Setter
public class Conference extends BaseEntity {

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String venue;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private boolean isActive;

    @Column(nullable = false)
    private boolean blindReview = false;

    private String logoUrl;
    private String contactEmail;

    @OneToMany(mappedBy = "conference", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SubTheme> subThemes = new ArrayList<>();

    @OneToMany(mappedBy = "conference", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConferenceCommitteeRole> committeeRoles = new ArrayList<>();

    @OneToOne(mappedBy = "conference", cascade = CascadeType.ALL, orphanRemoval = true)
    private ConferencePaymentConfig paymentConfig;
}
