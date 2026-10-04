package org.confcms.cms.paper;

import org.confcms.cms.conference.Conference;
import lombok.Getter;

import java.time.LocalDate;

@Getter
public class ConferenceSummaryDto {

    private final Long id;
    private final String title;
    private final LocalDate startDate;
    private final LocalDate endDate;

    private ConferenceSummaryDto(Long id, String title, LocalDate startDate, LocalDate endDate) {
        this.id = id;
        this.title = title;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public static ConferenceSummaryDto from(Conference conference) {
        return new ConferenceSummaryDto(
                conference.getId(),
                conference.getTitle(),
                conference.getStartDate(),
                conference.getEndDate()
        );
    }
}
