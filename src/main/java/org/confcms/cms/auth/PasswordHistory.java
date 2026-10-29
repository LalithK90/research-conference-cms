package org.confcms.cms.auth;

import org.confcms.cms.user.User;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.confcms.cms.core.domain.BaseEntity;

// One row per password a user has ever set, oldest first by createdAt (inherited from
// BaseEntity) -- never updated or deleted by anything except PasswordHistoryService's own
// retention trim. The hash is already bcrypt, so storing it here carries the same exposure as
// the live passwordHash column already does; nothing new is at risk by keeping old ones.
@Entity
@Table(name = "password_history")
@Getter
@Setter
public class PasswordHistory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String passwordHash;
}
