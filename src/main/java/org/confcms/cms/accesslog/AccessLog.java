package org.confcms.cms.accesslog;

import org.confcms.cms.user.User;

import org.confcms.cms.core.domain.BaseEntity;
import org.confcms.cms.paper.PaperVersion;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "access_logs")
@Getter
@Setter
public class AccessLog extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccessEventType eventType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String ipAddress;

    private String resolvedLocation;

    // Set only for MAGIC_LINK_REQUEST, where there may be no User to key off (the requested
    // email may not belong to any registered account). Kept as the raw string, not a User
    // reference, specifically so rate-limiting can key on it for unknown emails too --
    // otherwise the limiter itself would leak which emails are registered.
    private String requestedEmail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_version_id")
    private PaperVersion paperVersion;
}
