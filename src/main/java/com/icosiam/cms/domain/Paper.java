package com.icosiam.cms.domain;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class Paper extends BaseEntity {

    private Conference conference;

    private String title;

    private String abstractText;

    private User submitter;

    private PaperStatus status = PaperStatus.SUBMITTED;

    private SubTheme track;

    private List<PaperVersion> versions = new ArrayList<>();
    
    // Helper to generate reference code
    public String getReferenceCode() {
        return String.format("CONF%d-%04d", conference.getStartDate().getYear(), getId());
    }
}
