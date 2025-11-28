package com.icosiam.cms.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PaperVersion extends BaseEntity {

    private Paper paper;

    private Integer versionNumber;

    private String filePath;

    private String originalFilename;
}
