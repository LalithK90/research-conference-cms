package org.confcms.cms.submission.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthorRequestDto {

    private String fullName;
    private String email;
    private String affiliation;
    private boolean isPresenter;
}
