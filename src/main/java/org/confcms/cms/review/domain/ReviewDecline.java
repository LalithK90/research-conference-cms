package org.confcms.cms.review.domain;

import org.confcms.cms.core.domain.BaseEntity;
import org.confcms.cms.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "review_declines")
@Getter
@Setter
public class ReviewDecline extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", nullable = false, unique = true)
    private ReviewAssignment assignment;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suggested_user_id")
    private User suggestedUser;

    private String suggestedName;

    private String suggestedEmail;
}
