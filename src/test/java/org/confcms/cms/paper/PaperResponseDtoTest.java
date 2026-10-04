package org.confcms.cms.paper;

import org.confcms.cms.conference.Conference;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaperResponseDtoTest {

    @Test
    void fromMapsTopLevelFieldsConferenceVersionsAndAuthors() {
        Conference conference = new Conference();
        conference.setId(10L);
        conference.setTitle("Research Conference 2026");
        conference.setStartDate(LocalDate.of(2026, 11, 1));
        conference.setEndDate(LocalDate.of(2026, 11, 3));

        Paper paper = new Paper();
        paper.setId(1L);
        paper.setTitle("A Great Paper");
        paper.setAbstractText("An abstract.");
        paper.setTrack("Machine Learning");
        paper.setStatus(PaperStatus.SUBMITTED);
        paper.setConference(conference);

        PaperVersion version = new PaperVersion();
        version.setVersionNumber(1);
        paper.getVersions().add(version);

        PaperAuthor author = new PaperAuthor();
        author.setFullName("Ada Lovelace");
        paper.getAuthors().add(author);

        PaperResponseDto dto = PaperResponseDto.from(paper);

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getTitle()).isEqualTo("A Great Paper");
        assertThat(dto.getAbstractText()).isEqualTo("An abstract.");
        assertThat(dto.getTrack()).isEqualTo("Machine Learning");
        assertThat(dto.getStatus()).isEqualTo("SUBMITTED");
        assertThat(dto.getConference().getId()).isEqualTo(10L);
        assertThat(dto.getVersions()).hasSize(1);
        assertThat(dto.getAuthors()).extracting(PaperAuthorDto::getFullName).containsExactly("Ada Lovelace");
    }

    @Test
    void fromListMapsEachPaperInOrder() {
        Paper first = new Paper();
        first.setTitle("First");
        first.setConference(new Conference());
        Paper second = new Paper();
        second.setTitle("Second");
        second.setConference(new Conference());

        List<PaperResponseDto> dtos = PaperResponseDto.from(List.of(first, second));

        assertThat(dtos).extracting(PaperResponseDto::getTitle).containsExactly("First", "Second");
    }
}
