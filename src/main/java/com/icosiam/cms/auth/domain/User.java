package com.icosiam.cms.auth.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class User {

    private Long id;
    private String email;
    private String passwordHash;
    private String fullName;
    private String role;
    private boolean enabled = true;

}
