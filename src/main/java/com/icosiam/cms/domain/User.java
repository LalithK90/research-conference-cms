package com.icosiam.cms.domain;

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

    private String provider; // google, microsoft, local
    private String providerId;

    @Column(nullable = false)
    private boolean enabled = true;
}
