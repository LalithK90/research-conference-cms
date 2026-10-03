package org.confcms.cms.web.controller;

import org.confcms.cms.domain.CommitteeRole;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.ConferenceCommitteeRole;
import org.confcms.cms.domain.ConferencePaymentConfig;
import org.confcms.cms.domain.PaymentProvider;
import org.confcms.cms.domain.SubTheme;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.ConferenceRepository;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.ConferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminConferenceControllerTest {

    @Mock
    private ConferenceService conferenceService;
    @Mock
    private CommitteeService committeeService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ConferenceRepository conferenceRepository;

    private AdminConferenceController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminConferenceController(conferenceService, committeeService, userRepository, conferenceRepository);
    }

    @Test
    void saveConferenceRejectsMissingChair() {
        AdminConferenceController.ConferenceForm form = new AdminConferenceController.ConferenceForm();
        form.setTitle("Test Conf");
        form.setVenue("Venue");
        form.setStartDate(LocalDate.now());
        form.setEndDate(LocalDate.now().plusDays(1));
        form.setContactEmail("a@b.com");
        form.setPaymentProvider(PaymentProvider.FREE);
        form.setChairUserId(null);

        assertThatThrownBy(() -> controller.saveConference(form))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveConferenceAssignsChairWhenProvided() {
        AdminConferenceController.ConferenceForm form = new AdminConferenceController.ConferenceForm();
        form.setTitle("Test Conf");
        form.setVenue("Venue");
        form.setStartDate(LocalDate.now());
        form.setEndDate(LocalDate.now().plusDays(1));
        form.setContactEmail("a@b.com");
        form.setPaymentProvider(PaymentProvider.FREE);
        form.setChairUserId(7L);

        User chairUser = new User();
        chairUser.setId(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(chairUser));
        when(conferenceService.saveConference(any())).thenAnswer(inv -> {
            Conference c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        controller.saveConference(form);

        org.mockito.Mockito.verify(committeeService).assignChair(any(), org.mockito.Mockito.eq(chairUser));
    }

    @Test
    void saveConferenceSetsBlindReviewFlag() {
        AdminConferenceController.ConferenceForm form = new AdminConferenceController.ConferenceForm();
        form.setTitle("Test Conf");
        form.setVenue("Venue");
        form.setStartDate(LocalDate.now());
        form.setEndDate(LocalDate.now().plusDays(1));
        form.setContactEmail("a@b.com");
        form.setPaymentProvider(PaymentProvider.FREE);
        form.setChairUserId(7L);
        form.setBlindReview(true);

        User chairUser = new User();
        chairUser.setId(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(chairUser));
        when(conferenceService.saveConference(any())).thenAnswer(inv -> {
            Conference c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        controller.saveConference(form);

        org.mockito.Mockito.verify(conferenceService).saveConference(
                org.mockito.ArgumentMatchers.argThat(Conference::isBlindReview));
    }

    @Test
    void newConferenceFormPrefillsFromClonedConference() {
        Conference source = new Conference();
        source.setId(5L);
        source.setVenue("Grand Hall");
        source.setBlindReview(true);
        source.setLogoUrl("https://example.com/logo.png");
        source.setContactEmail("chair@example.com");

        ConferencePaymentConfig sourceConfig = new ConferencePaymentConfig();
        sourceConfig.setProvider(PaymentProvider.STRIPE);
        sourceConfig.setStripePublishableKey("pk_test_123");
        sourceConfig.setStripeSecretKey("sk_test_secret");
        source.setPaymentConfig(sourceConfig);

        User chair = new User();
        chair.setId(10L);
        User coChair = new User();
        coChair.setId(11L);

        ConferenceCommitteeRole chairRole = new ConferenceCommitteeRole();
        chairRole.setRole(CommitteeRole.CHAIR);
        chairRole.setUser(chair);
        ConferenceCommitteeRole coChairRole = new ConferenceCommitteeRole();
        coChairRole.setRole(CommitteeRole.CO_CHAIR);
        coChairRole.setUser(coChair);

        when(conferenceRepository.findById(5L)).thenReturn(Optional.of(source));
        when(committeeService.getCommitteeForConference(source)).thenReturn(List.of(chairRole, coChairRole));
        when(userRepository.findAll()).thenReturn(Collections.emptyList());
        when(conferenceRepository.findAll()).thenReturn(Collections.emptyList());

        Model model = new ExtendedModelMap();
        controller.newConferenceForm(5L, model);

        AdminConferenceController.ConferenceForm form =
                (AdminConferenceController.ConferenceForm) model.getAttribute("conferenceForm");

        assertThat(form.getVenue()).isEqualTo("Grand Hall");
        assertThat(form.isBlindReview()).isTrue();
        assertThat(form.getLogoUrl()).isEqualTo("https://example.com/logo.png");
        assertThat(form.getContactEmail()).isEqualTo("chair@example.com");
        assertThat(form.getChairUserId()).isEqualTo(10L);
        assertThat(form.getCoChairUserIds()).containsExactly(11L);
        assertThat(form.getPaymentProvider()).isEqualTo(PaymentProvider.STRIPE);
        assertThat(form.getStripePublishableKey()).isEqualTo("pk_test_123");
        assertThat(form.getStripeSecretKey()).isEqualTo("sk_test_secret");
        assertThat(form.getCloneFromConferenceId()).isEqualTo(5L);
        assertThat(form.getTitle()).isNull();
        assertThat(form.getStartDate()).isNull();
        assertThat(form.isActive()).isFalse();
    }

    @Test
    void newConferenceFormWithoutCloneFromIsBlank() {
        when(userRepository.findAll()).thenReturn(Collections.emptyList());
        when(conferenceRepository.findAll()).thenReturn(Collections.emptyList());

        Model model = new ExtendedModelMap();
        controller.newConferenceForm(null, model);

        AdminConferenceController.ConferenceForm form =
                (AdminConferenceController.ConferenceForm) model.getAttribute("conferenceForm");

        assertThat(form.getCloneFromConferenceId()).isNull();
        assertThat(form.getVenue()).isNull();
    }

    @Test
    void saveConferenceClonesSubThemesAndNonChairRolesWhenCloneFromIsSet() {
        AdminConferenceController.ConferenceForm form = new AdminConferenceController.ConferenceForm();
        form.setTitle("New Conf");
        form.setVenue("Venue");
        form.setStartDate(LocalDate.now());
        form.setEndDate(LocalDate.now().plusDays(1));
        form.setContactEmail("a@b.com");
        form.setPaymentProvider(PaymentProvider.FREE);
        form.setChairUserId(7L);
        form.setCloneFromConferenceId(5L);

        User chairUser = new User();
        chairUser.setId(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(chairUser));

        Conference savedConference = new Conference();
        savedConference.setId(20L);
        when(conferenceService.saveConference(any())).thenReturn(savedConference);

        Conference source = new Conference();
        source.setId(5L);
        SubTheme sourceTheme = new SubTheme();
        sourceTheme.setName("Machine Learning");
        sourceTheme.setDescription("ML track");
        source.getSubThemes().add(sourceTheme);
        when(conferenceRepository.findById(5L)).thenReturn(Optional.of(source));

        User reviewer = new User();
        reviewer.setId(30L);
        ConferenceCommitteeRole chairRole = new ConferenceCommitteeRole();
        chairRole.setRole(CommitteeRole.CHAIR);
        chairRole.setUser(chairUser);
        ConferenceCommitteeRole reviewerRole = new ConferenceCommitteeRole();
        reviewerRole.setRole(CommitteeRole.REVIEWER);
        reviewerRole.setUser(reviewer);
        reviewerRole.setDisplayTitle("Senior Reviewer");
        when(committeeService.getCommitteeForConference(source)).thenReturn(List.of(chairRole, reviewerRole));

        controller.saveConference(form);

        verify(conferenceRepository).save(argThatConferenceHasClonedSubTheme(savedConference));
        verify(committeeService).addRole(eq(savedConference), eq(reviewer), eq(CommitteeRole.REVIEWER), eq("Senior Reviewer"));
        verify(committeeService, never()).addRole(eq(savedConference), eq(chairUser), eq(CommitteeRole.CHAIR), any());
    }

    @Test
    void editConferenceFormPrefillsAllFieldsIncludingContent() {
        Conference conference = new Conference();
        conference.setId(9L);
        conference.setTitle("Existing Conf");
        conference.setVenue("Existing Venue");
        conference.setAboutHtml("<p>About text</p>");
        conference.setCallForPapersHtml("<p>CFP text</p>");
        conference.setVenueAddress("123 Main St");
        conference.setVenueMapEmbedUrl("https://maps.example.com/embed");
        conference.setTravelInfoHtml("<p>Travel text</p>");

        when(conferenceRepository.findById(9L)).thenReturn(Optional.of(conference));
        when(committeeService.getCommitteeForConference(conference)).thenReturn(List.of());
        when(userRepository.findAll()).thenReturn(Collections.emptyList());
        when(conferenceRepository.findAll()).thenReturn(Collections.emptyList());

        Model model = new ExtendedModelMap();
        controller.editConferenceForm(9L, model);

        AdminConferenceController.ConferenceForm form =
                (AdminConferenceController.ConferenceForm) model.getAttribute("conferenceForm");
        assertThat(form.getTitle()).isEqualTo("Existing Conf");
        assertThat(form.getAboutHtml()).isEqualTo("<p>About text</p>");
        assertThat(form.getCallForPapersHtml()).isEqualTo("<p>CFP text</p>");
        assertThat(form.getVenueAddress()).isEqualTo("123 Main St");
        assertThat(form.getVenueMapEmbedUrl()).isEqualTo("https://maps.example.com/embed");
        assertThat(form.getTravelInfoHtml()).isEqualTo("<p>Travel text</p>");
        assertThat(model.getAttribute("editingConferenceId")).isEqualTo(9L);
    }

    @Test
    void updateConferenceSavesContentFieldsOntoExistingConference() {
        Conference existing = new Conference();
        existing.setId(9L);
        when(conferenceRepository.findById(9L)).thenReturn(Optional.of(existing));
        when(conferenceService.saveConference(any())).thenAnswer(inv -> inv.getArgument(0));

        AdminConferenceController.ConferenceForm form = new AdminConferenceController.ConferenceForm();
        form.setTitle("Updated Title");
        form.setVenue("Updated Venue");
        form.setStartDate(LocalDate.now());
        form.setEndDate(LocalDate.now().plusDays(1));
        form.setContactEmail("a@b.com");
        form.setPaymentProvider(PaymentProvider.FREE);
        form.setAboutHtml("<p>Updated about</p>");

        String view = controller.updateConference(9L, form);

        assertThat(view).isEqualTo("redirect:/admin/conference/list");
        assertThat(existing.getTitle()).isEqualTo("Updated Title");
        assertThat(existing.getAboutHtml()).isEqualTo("<p>Updated about</p>");
    }

    @Test
    void updateConferenceSavesBlankContentFieldsAsNullNotEmptyString() {
        Conference existing = new Conference();
        existing.setId(9L);
        existing.setAboutHtml("<p>Old about</p>");
        when(conferenceRepository.findById(9L)).thenReturn(Optional.of(existing));
        when(conferenceService.saveConference(any())).thenAnswer(inv -> inv.getArgument(0));

        AdminConferenceController.ConferenceForm form = new AdminConferenceController.ConferenceForm();
        form.setTitle("Updated Title");
        form.setVenue("Updated Venue");
        form.setStartDate(LocalDate.now());
        form.setEndDate(LocalDate.now().plusDays(1));
        form.setContactEmail("a@b.com");
        form.setPaymentProvider(PaymentProvider.FREE);
        form.setAboutHtml("");
        form.setCallForPapersHtml("   ");
        form.setVenueAddress("");
        form.setTravelInfoHtml("");

        controller.updateConference(9L, form);

        assertThat(existing.getAboutHtml()).isNull();
        assertThat(existing.getCallForPapersHtml()).isNull();
        assertThat(existing.getVenueAddress()).isNull();
        assertThat(existing.getTravelInfoHtml()).isNull();
    }

    @Test
    void saveConferenceSavesBlankContentFieldsAsNullNotEmptyString() {
        when(conferenceService.saveConference(any())).thenAnswer(inv -> inv.getArgument(0));

        AdminConferenceController.ConferenceForm form = new AdminConferenceController.ConferenceForm();
        form.setTitle("New Conf");
        form.setVenue("New Venue");
        form.setStartDate(LocalDate.now());
        form.setEndDate(LocalDate.now().plusDays(1));
        form.setChairUserId(1L);
        form.setPaymentProvider(PaymentProvider.FREE);
        form.setAboutHtml("");
        form.setCallForPapersHtml("");
        form.setVenueAddress("");
        form.setTravelInfoHtml("");
        when(userRepository.findById(1L)).thenReturn(Optional.of(new User()));

        controller.saveConference(form);

        org.mockito.Mockito.verify(conferenceService).saveConference(org.mockito.ArgumentMatchers.argThat(c ->
                c.getAboutHtml() == null && c.getCallForPapersHtml() == null
                        && c.getVenueAddress() == null && c.getTravelInfoHtml() == null));
    }

    private Conference argThatConferenceHasClonedSubTheme(Conference expected) {
        return org.mockito.ArgumentMatchers.argThat(c -> c == expected
                && c.getSubThemes().size() == 1
                && "Machine Learning".equals(c.getSubThemes().get(0).getName())
                && c.getSubThemes().get(0).getConference() == expected);
    }
}
