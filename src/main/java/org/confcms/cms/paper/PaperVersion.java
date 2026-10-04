package org.confcms.cms.paper;

import org.confcms.cms.core.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

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
    @ColumnDefault("false")
    private boolean possibleDuplicate = false;

    private Long duplicateOfPaperVersionId;

    // Manually entered by staff after checking this version outside the system (e.g. Turnitin,
    // iThenticate) -- no automated checking happens here. Nullable: absent until someone records it.
    private Double plagiarismScore;

    @Column(columnDefinition = "TEXT")
    private String plagiarismNote;

    // True only for the version uploaded specifically as the camera-ready submission
    // (via SubmissionService.uploadCameraReady), never for a regular version/revision upload.
    @Column(nullable = false)
    private boolean cameraReady = false;

    // Set the moment the author checks "I agree to transfer copyright" during camera-ready
    // upload. Null until then -- no separate entity/e-signature workflow in this pass.
    private java.time.Instant copyrightTransferAgreedAt;
}
