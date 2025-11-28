package com.icosiam.cms.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Review extends BaseEntity {

    private Paper paper;

    private User reviewer;

    private Integer score; // 1-5

    private String comments;

    private String confidentialComments;

    private ReviewDecision decision;
}
