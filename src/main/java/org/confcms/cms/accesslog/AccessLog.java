package org.confcms.cms.accesslog;

import org.confcms.cms.user.User;

import org.confcms.cms.core.domain.BaseEntity;
import org.confcms.cms.submission.domain.PaperVersion;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_version_id")
    private PaperVersion paperVersion;
}
