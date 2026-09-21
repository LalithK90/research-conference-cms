package org.confcms.cms.domain;

import org.confcms.cms.core.domain.BaseEntity;
import org.confcms.cms.core.security.Role;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
public class User extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String email;

    private String passwordHash; // Nullable for OAuth2 users

    @Column(nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    private String orcidId; // nullable; populated on ORCID OAuth2 login/link, denormalized from UserIdentity for cheap author-disambiguation lookups later

    @Column(nullable = false)
    private boolean enabled = true;
}
