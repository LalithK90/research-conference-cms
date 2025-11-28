package com.icosiam.cms.review.domain;

import com.icosiam.cms.domain.BaseEntity;
import com.icosiam.cms.domain.User;
import com.icosiam.cms.submission.domain.Paper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "review_bids")
@Getter
@Setter
public class ReviewBid extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private User reviewer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_id", nullable = false)
    private Paper paper;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BidType bidType;

    private String conflictReason; // Only used if bidType is CONFLICT
}
