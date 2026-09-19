package org.confcms.cms.review.domain;

import org.confcms.cms.core.domain.BaseEntity;
import org.confcms.cms.domain.User;
import org.confcms.cms.submission.domain.Paper;
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
