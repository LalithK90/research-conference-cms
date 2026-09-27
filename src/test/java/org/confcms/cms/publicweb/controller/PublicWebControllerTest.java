package org.confcms.cms.publicweb.controller;

import org.confcms.cms.repository.ConferenceRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.ConferenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class PublicWebControllerTest {

    @Mock
    private ConferenceService conferenceService;
    @Mock
    private CommitteeService committeeService;
    @Mock
    private ClientRegistrationRepository clientRegistrationRepository;
    @Mock
    private ConferenceRepository conferenceRepository;
    @Mock
    private org.confcms.cms.repository.SpeakerRepository speakerRepository;
    @Mock
    private org.confcms.cms.repository.SponsorRepository sponsorRepository;

    private PublicWebController controller() {
        return new PublicWebController(conferenceService, committeeService, clientRegistrationRepository,
                conferenceRepository, speakerRepository, sponsorRepository);
    }

    @Test
    void aboutReturnsAboutView() {
        Model model = new ExtendedModelMap();
        String view = controller().about(model);
        assertThat(view).isEqualTo("public/about");
    }

    @Test
    void contactReturnsContactView() {
        Model model = new ExtendedModelMap();
        String view = controller().contact(model);
        assertThat(view).isEqualTo("public/contact");
    }

    @Test
    void speakersSplitsIntoPlenaryAndKeynoteLists() {
        org.confcms.cms.domain.Conference conference = new org.confcms.cms.domain.Conference();
        conference.setId(1L);
        org.mockito.Mockito.when(conferenceService.getActiveConference()).thenReturn(conference);

        org.confcms.cms.domain.Speaker plenary = new org.confcms.cms.domain.Speaker();
        plenary.setType(org.confcms.cms.domain.SpeakerType.PLENARY);
        org.confcms.cms.domain.Speaker keynote = new org.confcms.cms.domain.Speaker();
        keynote.setType(org.confcms.cms.domain.SpeakerType.KEYNOTE);
        org.mockito.Mockito.when(speakerRepository.findByConferenceIdOrderByDisplayOrderAsc(1L))
                .thenReturn(java.util.List.of(plenary, keynote));

        Model model = new ExtendedModelMap();
        String view = controller().speakers(model);

        assertThat(view).isEqualTo("public/speakers");
        assertThat((java.util.List<Object>) model.getAttribute("plenarySpeakers")).containsExactly(plenary);
        assertThat((java.util.List<Object>) model.getAttribute("keynoteSpeakers")).containsExactly(keynote);
    }

    @Test
    void sponsorsGroupsByTierInDeclarationOrder() {
        org.confcms.cms.domain.Conference conference = new org.confcms.cms.domain.Conference();
        conference.setId(1L);
        org.mockito.Mockito.when(conferenceService.getActiveConference()).thenReturn(conference);

        org.confcms.cms.domain.Sponsor gold = new org.confcms.cms.domain.Sponsor();
        gold.setTier(org.confcms.cms.domain.SponsorTier.GOLD);
        org.confcms.cms.domain.Sponsor platinum = new org.confcms.cms.domain.Sponsor();
        platinum.setTier(org.confcms.cms.domain.SponsorTier.PLATINUM);
        org.mockito.Mockito.when(sponsorRepository.findByConferenceIdOrderByDisplayOrderAsc(1L))
                .thenReturn(java.util.List.of(gold, platinum));

        Model model = new ExtendedModelMap();
        String view = controller().sponsors(model);

        assertThat(view).isEqualTo("public/sponsors");
        @SuppressWarnings("unchecked")
        java.util.Map<org.confcms.cms.domain.SponsorTier, java.util.List<org.confcms.cms.domain.Sponsor>> byTier =
                (java.util.Map<org.confcms.cms.domain.SponsorTier, java.util.List<org.confcms.cms.domain.Sponsor>>) model.getAttribute("sponsorsByTier");
        assertThat(byTier.get(org.confcms.cms.domain.SponsorTier.PLATINUM)).containsExactly(platinum);
        assertThat(byTier.get(org.confcms.cms.domain.SponsorTier.GOLD)).containsExactly(gold);
        assertThat(byTier.get(org.confcms.cms.domain.SponsorTier.SILVER)).isEmpty();
    }

    @Test
    void callForPapersReturnsCallForPapersView() {
        Model model = new ExtendedModelMap();
        String view = controller().callForPapers(model);
        assertThat(view).isEqualTo("public/call_for_papers");
    }

    @Test
    void venueReturnsVenueView() {
        Model model = new ExtendedModelMap();
        String view = controller().venue(model);
        assertThat(view).isEqualTo("public/venue");
    }

    @Test
    void pastConferencesListsOnlyInactiveConferencesNewestFirst() {
        org.confcms.cms.domain.Conference active = new org.confcms.cms.domain.Conference();
        active.setActive(true);
        active.setStartDate(java.time.LocalDate.of(2026, 1, 1));

        org.confcms.cms.domain.Conference older = new org.confcms.cms.domain.Conference();
        older.setActive(false);
        older.setStartDate(java.time.LocalDate.of(2024, 1, 1));

        org.confcms.cms.domain.Conference newer = new org.confcms.cms.domain.Conference();
        newer.setActive(false);
        newer.setStartDate(java.time.LocalDate.of(2025, 1, 1));

        org.mockito.Mockito.when(conferenceRepository.findAll()).thenReturn(java.util.List.of(active, older, newer));

        Model model = new ExtendedModelMap();
        String view = controller().pastConferences(model);

        assertThat(view).isEqualTo("public/past_conferences");
        assertThat((java.util.List<Object>) model.getAttribute("pastConferences")).containsExactly(newer, older);
    }

    @Test
    void pastConferenceAboutLoadsTheRequestedConferenceNotTheActiveOne() {
        org.confcms.cms.domain.Conference target = new org.confcms.cms.domain.Conference();
        target.setId(42L);
        target.setTitle("2024 Edition");
        org.mockito.Mockito.when(conferenceRepository.findById(42L)).thenReturn(java.util.Optional.of(target));

        Model model = new ExtendedModelMap();
        String view = controller().pastConferenceAbout(42L, model);

        assertThat(view).isEqualTo("public/about");
        assertThat(model.getAttribute("conference")).isEqualTo(target);
        org.mockito.Mockito.verify(conferenceService, org.mockito.Mockito.never()).getActiveConference();
    }
}
