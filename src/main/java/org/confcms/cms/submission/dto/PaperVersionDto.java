package org.confcms.cms.submission.dto;

import org.confcms.cms.submission.domain.PaperVersion;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
public class PaperVersionDto {

    private final Long id;
    private final Integer versionNumber;
    private final String originalFilename;
    private final boolean possibleDuplicate;
    private final boolean cameraReady;
    private final Instant copyrightTransferAgreedAt;

    private PaperVersionDto(Long id, Integer versionNumber, String originalFilename,
                             boolean possibleDuplicate, boolean cameraReady,
                             Instant copyrightTransferAgreedAt) {
        this.id = id;
        this.versionNumber = versionNumber;
        this.originalFilename = originalFilename;
        this.possibleDuplicate = possibleDuplicate;
        this.cameraReady = cameraReady;
        this.copyrightTransferAgreedAt = copyrightTransferAgreedAt;
    }

    public static PaperVersionDto from(PaperVersion version) {
        return new PaperVersionDto(
                version.getId(),
                version.getVersionNumber(),
                version.getOriginalFilename(),
                version.isPossibleDuplicate(),
                version.isCameraReady(),
                version.getCopyrightTransferAgreedAt()
        );
    }

    public static List<PaperVersionDto> from(List<PaperVersion> versions) {
        return versions.stream().map(PaperVersionDto::from).toList();
    }
}
