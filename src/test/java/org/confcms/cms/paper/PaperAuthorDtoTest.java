package org.confcms.cms.paper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaperAuthorDtoTest {

    @Test
    void fromMapsAllFieldsFromPaperAuthor() {
        PaperAuthor author = new PaperAuthor();
        author.setId(5L);
        author.setFullName("Ada Lovelace");
        author.setEmail("ada@example.com");
        author.setAffiliation("Analytical Engines Inc");
        author.setPresenter(true);

        PaperAuthorDto dto = PaperAuthorDto.from(author);

        assertThat(dto.getId()).isEqualTo(5L);
        assertThat(dto.getFullName()).isEqualTo("Ada Lovelace");
        assertThat(dto.getEmail()).isEqualTo("ada@example.com");
        assertThat(dto.getAffiliation()).isEqualTo("Analytical Engines Inc");
        assertThat(dto.isPresenter()).isTrue();
    }

    @Test
    void fromListMapsEachAuthorInOrder() {
        PaperAuthor first = new PaperAuthor();
        first.setFullName("Ada Lovelace");
        PaperAuthor second = new PaperAuthor();
        second.setFullName("Grace Hopper");

        List<PaperAuthorDto> dtos = PaperAuthorDto.from(List.of(first, second));

        assertThat(dtos).extracting(PaperAuthorDto::getFullName)
                .containsExactly("Ada Lovelace", "Grace Hopper");
    }
}
