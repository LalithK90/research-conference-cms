package org.confcms.cms.submission.dto;

import org.confcms.cms.domain.Conference;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ConferenceSummaryDtoTest {

    @Test
    void fromMapsIdTitleAndDatesButNotSubThemes() {
        Conference conference = new Conference();
        conference.setId(10L);
        conference.setTitle("Research Conference 2026");
        conference.setStartDate(LocalDate.of(2026, 11, 1));
        conference.setEndDate(LocalDate.of(2026, 11, 3));

        ConferenceSummaryDto dto = ConferenceSummaryDto.from(conference);

        assertThat(dto.getId()).isEqualTo(10L);
        assertThat(dto.getTitle()).isEqualTo("Research Conference 2026");
        assertThat(dto.getStartDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(dto.getEndDate()).isEqualTo(LocalDate.of(2026, 11, 3));
    }
}
