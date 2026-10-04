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

    // MAGIC_LINK_REQUEST only: whether this specific request actually triggered a real email
    // send (false for a rate-limited repeat, or an unknown email). Lets the per-email send
    // cap count real sends, not every logged request -- a request that was itself
    // rate-limited shouldn't count against a separate, looser per-email ceiling.
    private boolean emailSent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_version_id")
    private PaperVersion paperVersion;
}
