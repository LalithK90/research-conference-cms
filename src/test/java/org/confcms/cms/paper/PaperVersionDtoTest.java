package org.confcms.cms.paper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaperVersionDtoTest {

    @Test
    void fromMapsFieldsButExcludesFilePathAndPlagiarismFields() {
        PaperVersion version = new PaperVersion();
        version.setId(3L);
        version.setVersionNumber(2);
        version.setOriginalFilename("paper-v2.pdf");
        version.setFilePath("/var/app/uploads/uuid_paper-v2.pdf");
        version.setPossibleDuplicate(true);
        version.setCameraReady(true);
        version.setPlagiarismScore(12.5);
        version.setPlagiarismNote("Checked with Turnitin");
        Instant agreedAt = Instant.parse("2026-10-01T12:00:00Z");
        version.setCopyrightTransferAgreedAt(agreedAt);

        PaperVersionDto dto = PaperVersionDto.from(version);

        assertThat(dto.getId()).isEqualTo(3L);
        assertThat(dto.getVersionNumber()).isEqualTo(2);
        assertThat(dto.getOriginalFilename()).isEqualTo("paper-v2.pdf");
        assertThat(dto.isPossibleDuplicate()).isTrue();
        assertThat(dto.isCameraReady()).isTrue();
        assertThat(dto.getCopyrightTransferAgreedAt()).isEqualTo(agreedAt);
    }

    @Test
    void fromListMapsEachVersionInOrder() {
        PaperVersion v1 = new PaperVersion();
        v1.setVersionNumber(1);
        PaperVersion v2 = new PaperVersion();
        v2.setVersionNumber(2);

        List<PaperVersionDto> dtos = PaperVersionDto.from(List.of(v1, v2));

        assertThat(dtos).extracting(PaperVersionDto::getVersionNumber).containsExactly(1, 2);
    }
}
