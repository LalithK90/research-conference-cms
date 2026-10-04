package org.confcms.cms.publicweb.controller;

import org.confcms.cms.conference.ConferenceRepository;
import org.confcms.cms.conference.CommitteeService;
import org.confcms.cms.conference.ConferenceService;
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
    private org.confcms.cms.speaker.SpeakerRepository speakerRepository;
    @Mock
    private org.confcms.cms.sponsor.SponsorRepository sponsorRepository;

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
        org.confcms.cms.conference.Conference conference = new org.confcms.cms.conference.Conference();
        conference.setId(1L);
        org.mockito.Mockito.when(conferenceService.getActiveConference()).thenReturn(conference);

        org.confcms.cms.speaker.Speaker plenary = new org.confcms.cms.speaker.Speaker();
        plenary.setType(org.confcms.cms.speaker.SpeakerType.PLENARY);
        org.confcms.cms.speaker.Speaker keynote = new org.confcms.cms.speaker.Speaker();
        keynote.setType(org.confcms.cms.speaker.SpeakerType.KEYNOTE);
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
        org.confcms.cms.conference.Conference conference = new org.confcms.cms.conference.Conference();
        conference.setId(1L);
        org.mockito.Mockito.when(conferenceService.getActiveConference()).thenReturn(conference);

        org.confcms.cms.sponsor.Sponsor gold = new org.confcms.cms.sponsor.Sponsor();
        gold.setTier(org.confcms.cms.sponsor.SponsorTier.GOLD);
        org.confcms.cms.sponsor.Sponsor platinum = new org.confcms.cms.sponsor.Sponsor();
        platinum.setTier(org.confcms.cms.sponsor.SponsorTier.PLATINUM);
        org.mockito.Mockito.when(sponsorRepository.findByConferenceIdOrderByDisplayOrderAsc(1L))
                .thenReturn(java.util.List.of(gold, platinum));

        Model model = new ExtendedModelMap();
        String view = controller().sponsors(model);

        assertThat(view).isEqualTo("public/sponsors");
        @SuppressWarnings("unchecked")
        java.util.Map<org.confcms.cms.sponsor.SponsorTier, java.util.List<org.confcms.cms.sponsor.Sponsor>> byTier =
                (java.util.Map<org.confcms.cms.sponsor.SponsorTier, java.util.List<org.confcms.cms.sponsor.Sponsor>>) model.getAttribute("sponsorsByTier");
        assertThat(byTier.get(org.confcms.cms.sponsor.SponsorTier.PLATINUM)).containsExactly(platinum);
        assertThat(byTier.get(org.confcms.cms.sponsor.SponsorTier.GOLD)).containsExactly(gold);
        assertThat(byTier.get(org.confcms.cms.sponsor.SponsorTier.SILVER)).isEmpty();
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
        org.confcms.cms.conference.Conference active = new org.confcms.cms.conference.Conference();
        active.setActive(true);
        active.setStartDate(java.time.LocalDate.of(2026, 1, 1));

        org.confcms.cms.conference.Conference older = new org.confcms.cms.conference.Conference();
        older.setActive(false);
        older.setStartDate(java.time.LocalDate.of(2024, 1, 1));

        org.confcms.cms.conference.Conference newer = new org.confcms.cms.conference.Conference();
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
        org.confcms.cms.conference.Conference target = new org.confcms.cms.conference.Conference();
        target.setId(42L);
        target.setTitle("2024 Edition");
        org.mockito.Mockito.when(conferenceRepository.findById(42L)).thenReturn(java.util.Optional.of(target));

        Model model = new ExtendedModelMap();
        String view = controller().pastConferenceAbout(42L, model);

        assertThat(view).isEqualTo("public/about");
        assertThat(model.getAttribute("conference")).isEqualTo(target);
        org.mockito.Mockito.verify(conferenceService, org.mockito.Mockito.never()).getActiveConference();
    }

    // The shared nav fragment needs to know it's rendering a past conference's page (and which
    // one) so its About/Committee/Speakers/Sponsors links point back at that same conference's
    // past-conferences/{id}/... routes instead of the live site's -- without this, those links
    // silently land on the active conference's page no matter which past conference is showing.
    @Test
    void pastConferenceAboutSetsPastConferenceIdForNav() {
        org.confcms.cms.conference.Conference target = new org.confcms.cms.conference.Conference();
        target.setId(42L);
        org.mockito.Mockito.when(conferenceRepository.findById(42L)).thenReturn(java.util.Optional.of(target));

        Model model = new ExtendedModelMap();
        controller().pastConferenceAbout(42L, model);

        assertThat(model.getAttribute("pastConferenceId")).isEqualTo(42L);
    }

    @Test
    void pastConferenceCommitteeSetsPastConferenceIdForNav() {
        org.confcms.cms.conference.Conference target = new org.confcms.cms.conference.Conference();
        target.setId(43L);
        org.mockito.Mockito.when(conferenceRepository.findById(43L)).thenReturn(java.util.Optional.of(target));
        org.mockito.Mockito.when(committeeService.getCommitteeForConference(target)).thenReturn(java.util.List.of());

        Model model = new ExtendedModelMap();
        controller().pastConferenceCommittee(43L, model);

        assertThat(model.getAttribute("pastConferenceId")).isEqualTo(43L);
    }

    @Test
    void pastConferenceSpeakersSetsPastConferenceIdForNav() {
        org.confcms.cms.conference.Conference target = new org.confcms.cms.conference.Conference();
        target.setId(44L);
        org.mockito.Mockito.when(conferenceRepository.findById(44L)).thenReturn(java.util.Optional.of(target));
        org.mockito.Mockito.when(speakerRepository.findByConferenceIdOrderByDisplayOrderAsc(44L))
                .thenReturn(java.util.List.of());

        Model model = new ExtendedModelMap();
        controller().pastConferenceSpeakers(44L, model);

        assertThat(model.getAttribute("pastConferenceId")).isEqualTo(44L);
    }

    @Test
    void pastConferenceSponsorsSetsPastConferenceIdForNav() {
        org.confcms.cms.conference.Conference target = new org.confcms.cms.conference.Conference();
        target.setId(45L);
        org.mockito.Mockito.when(conferenceRepository.findById(45L)).thenReturn(java.util.Optional.of(target));
        org.mockito.Mockito.when(sponsorRepository.findByConferenceIdOrderByDisplayOrderAsc(45L))
                .thenReturn(java.util.List.of());

        Model model = new ExtendedModelMap();
        controller().pastConferenceSponsors(45L, model);

        assertThat(model.getAttribute("pastConferenceId")).isEqualTo(45L);
    }

    // The live (active-conference) handlers must NOT set pastConferenceId -- its mere presence
    // in the model is what tells the nav fragment "you're viewing a past conference", so leaking
    // a stale value here would make the live site's nav wrongly link into past-conferences/**.
    @Test
    void aboutDoesNotSetPastConferenceId() {
        Model model = new ExtendedModelMap();
        controller().about(model);

        assertThat(model.containsAttribute("pastConferenceId")).isFalse();
    }
}
