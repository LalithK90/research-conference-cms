package org.confcms.cms.submission.domain;

import org.confcms.cms.core.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "paper_versions")
@Getter
@Setter
public class PaperVersion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_id", nullable = false)
    private Paper paper;

    @Column(nullable = false)
    private Integer versionNumber;

    @Column(nullable = false)
    private String filePath;

    @Column(nullable = false)
    private String originalFilename;

    @Column(length = 64)
    private String contentHash;

    @Column(nullable = false)
    private boolean possibleDuplicate = false;

    private Long duplicateOfPaperVersionId;

    // Manually entered by staff after checking this version outside the system (e.g. Turnitin,
    // iThenticate) -- no automated checking happens here. Nullable: absent until someone records it.
    private Double plagiarismScore;

    @Column(columnDefinition = "TEXT")
    private String plagiarismNote;
}
