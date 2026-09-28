package org.confcms.cms.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.Sponsor;
import org.confcms.cms.domain.SponsorTier;
import org.confcms.cms.repository.ConferenceRepository;
import org.confcms.cms.repository.SponsorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSponsorControllerTest {

    @Mock
    private SponsorRepository sponsorRepository;
    @Mock
    private ConferenceRepository conferenceRepository;

    private AdminSponsorController controller;
    private Conference conference;

    @BeforeEach
    void setUp() {
        controller = new AdminSponsorController(sponsorRepository, conferenceRepository);
        conference = new Conference();
        conference.setId(1L);
        conference.setTitle("Test Conf");
    }

    @Test
    void listShowsSponsorsForConference() {
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        Sponsor sponsor = new Sponsor();
        when(sponsorRepository.findByConferenceIdOrderByDisplayOrderAsc(1L)).thenReturn(List.of(sponsor));

        Model model = new ExtendedModelMap();
        String view = controller.list(1L, model);

        assertThat(view).isEqualTo("admin/sponsors");
        assertThat((List<Object>) model.getAttribute("sponsors")).containsExactly(sponsor);
    }

    @Test
    void saveAttachesConferenceAndPersists() {
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        Sponsor sponsor = new Sponsor();
        sponsor.setName("Acme Corp");

        String view = controller.save(1L, sponsor);

        assertThat(view).isEqualTo("redirect:/admin/conference/1/sponsors");
        assertThat(sponsor.getConference()).isEqualTo(conference);
        verify(sponsorRepository).save(sponsor);
    }

    @Test
    void deleteRemovesSponsorById() {
        String view = controller.delete(1L, 5L);

        assertThat(view).isEqualTo("redirect:/admin/conference/1/sponsors");
        verify(sponsorRepository).deleteById(5L);
    }

    @Test
    void editFormLoadsExistingSponsor() {
        Sponsor sponsor = new Sponsor();
        sponsor.setId(5L);
        sponsor.setTier(SponsorTier.GOLD);
        when(sponsorRepository.findById(5L)).thenReturn(Optional.of(sponsor));
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));

        Model model = new ExtendedModelMap();
        String view = controller.editForm(1L, 5L, model);

        assertThat(view).isEqualTo("admin/sponsor_form");
        assertThat(model.getAttribute("sponsor")).isEqualTo(sponsor);
    }
}
