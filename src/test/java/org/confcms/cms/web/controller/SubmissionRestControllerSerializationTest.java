package org.confcms.cms.web.controller;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.conference.SubTheme;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperAuthor;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.domain.PaperVersion;
import org.confcms.cms.submission.dto.PaperResponseDto;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SubmissionRestControllerSerializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void paperResponseDtoSerializesFiniteJsonWithNoCycleAndNoSensitiveFields() {
        // Reconstruct cycle #1: Conference.subThemes <-> SubTheme.conference
        Conference conference = new Conference();
        conference.setId(10L);
        conference.setTitle("Research Conference 2026");
        conference.setStartDate(LocalDate.of(2026, 11, 1));
        conference.setEndDate(LocalDate.of(2026, 11, 3));
        SubTheme subTheme = new SubTheme();
        subTheme.setName("Machine Learning");
        subTheme.setConference(conference);
        conference.getSubThemes().add(subTheme);

        Paper paper = new Paper();
        paper.setId(1L);
        paper.setTitle("A Great Paper");
        paper.setAbstractText("An abstract.");
        paper.setTrack("Machine Learning");
        paper.setStatus(PaperStatus.SUBMITTED);
        paper.setConference(conference);

        PaperVersion version = new PaperVersion();
        version.setVersionNumber(1);
        version.setOriginalFilename("paper.pdf");
        version.setFilePath("/var/app/uploads/uuid_paper.pdf");
        version.setPaper(paper);
        paper.getVersions().add(version);

        // Reconstruct cycle #2: Paper.authors <-> PaperAuthor.paper
        PaperAuthor author = new PaperAuthor();
        author.setFullName("Ada Lovelace");
        author.setEmail("ada@example.com");
        author.setAffiliation("Analytical Engines Inc");
        author.setPaper(paper);
        paper.getAuthors().add(author);

        PaperResponseDto dto = PaperResponseDto.from(paper);

        String json = jsonMapper.writeValueAsString(dto);

        assertThat(json).doesNotContain("passwordHash");
        assertThat(json).doesNotContain("filePath");
        assertThat(json).doesNotContain("subThemes");
        assertThat(json).contains("\"title\":\"A Great Paper\"");
        assertThat(json).contains("\"fullName\":\"Ada Lovelace\"");
        // A real recursive response from the old bug was ~34-140KB; the fixed shape is tiny.
        assertThat(json.length()).isLessThan(2000);
    }
}
