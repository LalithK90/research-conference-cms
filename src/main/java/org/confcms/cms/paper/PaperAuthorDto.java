package org.confcms.cms.paper;

import lombok.Getter;

import java.util.List;

@Getter
public class PaperAuthorDto {

    private final Long id;
    private final String fullName;
    private final String email;
    private final String affiliation;
    private final boolean presenter;

    private PaperAuthorDto(Long id, String fullName, String email, String affiliation, boolean presenter) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.affiliation = affiliation;
        this.presenter = presenter;
    }

    public static PaperAuthorDto from(PaperAuthor author) {
        return new PaperAuthorDto(
                author.getId(),
                author.getFullName(),
                author.getEmail(),
                author.getAffiliation(),
                author.isPresenter()
        );
    }

    public static List<PaperAuthorDto> from(List<PaperAuthor> authors) {
        return authors.stream().map(PaperAuthorDto::from).toList();
    }
}
