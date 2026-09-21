package org.confcms.cms.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.confcms.cms.core.domain.BaseEntity;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_identities", uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "provider_user_id"}))
@Getter
@Setter
public class UserIdentity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String provider; // "local" | "google" | "orcid"

    @Column(name = "provider_user_id")
    private String providerUserId; // Google's "sub", ORCID's iD; null for "local"

    @Column(nullable = false)
    private LocalDateTime linkedAt;
}
