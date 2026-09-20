package org.confcms.cms.domain;

import org.confcms.cms.core.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "conflict_declarations",
       uniqueConstraints = @UniqueConstraint(columnNames = {"conference_id", "reviewer_id", "declared_against_user_id"}))
@Getter
@Setter
public class ConflictDeclaration extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private User reviewer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "declared_against_user_id", nullable = false)
    private User declaredAgainstUser;
}
