package org.confcms.cms.paper;

import lombok.Getter;

import java.util.List;

@Getter
public class PaperResponseDto {

    private final Long id;
    private final String title;
    private final String abstractText;
    private final String track;
    private final String status;
    private final ConferenceSummaryDto conference;
    private final List<PaperVersionDto> versions;
    private final List<PaperAuthorDto> authors;

    private PaperResponseDto(Long id, String title, String abstractText, String track, String status,
                              ConferenceSummaryDto conference, List<PaperVersionDto> versions,
                              List<PaperAuthorDto> authors) {
        this.id = id;
        this.title = title;
        this.abstractText = abstractText;
        this.track = track;
        this.status = status;
        this.conference = conference;
        this.versions = versions;
        this.authors = authors;
    }

    public static PaperResponseDto from(Paper paper) {
        return new PaperResponseDto(
                paper.getId(),
                paper.getTitle(),
                paper.getAbstractText(),
                paper.getTrack(),
                paper.getStatus() != null ? paper.getStatus().name() : null,
                ConferenceSummaryDto.from(paper.getConference()),
                PaperVersionDto.from(paper.getVersions()),
                PaperAuthorDto.from(paper.getAuthors())
        );
    }

    public static List<PaperResponseDto> from(List<Paper> papers) {
        return papers.stream().map(PaperResponseDto::from).toList();
    }
}
