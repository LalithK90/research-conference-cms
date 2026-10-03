package org.confcms.cms.web.controller;

import org.confcms.cms.domain.CommitteeRole;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.ConferenceCommitteeRole;
import org.confcms.cms.domain.ConferencePaymentConfig;
import org.confcms.cms.domain.PaymentProvider;
import org.confcms.cms.domain.SubTheme;
import org.confcms.cms.user.User;
import org.confcms.cms.repository.ConferenceRepository;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.ConferenceService;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/admin/conference")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminConferenceController {

    private final ConferenceService conferenceService;
    private final CommitteeService committeeService;
    private final UserRepository userRepository;
    private final ConferenceRepository conferenceRepository;

    @GetMapping("/new")
    public String newConferenceForm(@RequestParam(required = false) Long cloneFrom, Model model) {
        ConferenceForm form = cloneFrom != null
                ? buildFormFromClonedConference(cloneFrom)
                : new ConferenceForm();
        model.addAttribute("conferenceForm", form);
        model.addAttribute("allUsers", userRepository.findAll());
        model.addAttribute("allConferences", conferenceRepository.findAll());
        return "admin/conference_form";
    }

    // Prefills the create-conference form from an existing conference (venue, blind-review,
    // logo/contact, chair/co-chairs, and payment config), but never copies title/dates/active --
    // those must always be entered fresh for a new conference. cloneFromConferenceId is carried
    // through so saveConference can also copy sub-themes and non-chair committee roles, which have
    // no dedicated form fields to prefill.
    private ConferenceForm buildFormFromClonedConference(Long sourceId) {
        Conference source = conferenceRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Conference to clone from not found"));

        ConferenceForm form = new ConferenceForm();
        form.setVenue(source.getVenue());
        form.setBlindReview(source.isBlindReview());
        form.setLogoUrl(source.getLogoUrl());
        form.setContactEmail(source.getContactEmail());
        form.setCloneFromConferenceId(sourceId);
        form.setAboutHtml(source.getAboutHtml());
        form.setCallForPapersHtml(source.getCallForPapersHtml());
        form.setVenueAddress(source.getVenueAddress());
        form.setVenueMapEmbedUrl(source.getVenueMapEmbedUrl());
        form.setTravelInfoHtml(source.getTravelInfoHtml());

        for (ConferenceCommitteeRole role : committeeService.getCommitteeForConference(source)) {
            if (role.getRole() == CommitteeRole.CHAIR) {
                form.setChairUserId(role.getUser().getId());
            } else if (role.getRole() == CommitteeRole.CO_CHAIR) {
                form.getCoChairUserIds().add(role.getUser().getId());
            }
        }

        ConferencePaymentConfig sourceConfig = source.getPaymentConfig();
        if (sourceConfig != null) {
            form.setPaymentProvider(sourceConfig.getProvider());
            form.setStripePublishableKey(sourceConfig.getStripePublishableKey());
            form.setStripeSecretKey(sourceConfig.getStripeSecretKey());
            form.setPaypalClientId(sourceConfig.getPaypalClientId());
            form.setPaypalClientSecret(sourceConfig.getPaypalClientSecret());
            form.setBankDetails(sourceConfig.getBankDetails());
        }

        return form;
    }

    @PostMapping("/save")
    public String saveConference(@ModelAttribute ConferenceForm form) {
        if (form.getChairUserId() == null) {
            throw new IllegalArgumentException("A Chair must be selected to create a conference");
        }
        User chairUser = userRepository.findById(form.getChairUserId())
                .orElseThrow(() -> new IllegalArgumentException("Selected Chair not found"));

        Conference conference = new Conference();
        conference.setTitle(form.getTitle());
        conference.setVenue(form.getVenue());
        conference.setStartDate(form.getStartDate());
        conference.setEndDate(form.getEndDate());
        conference.setActive(form.isActive());
        conference.setBlindReview(form.isBlindReview());
        conference.setLogoUrl(form.getLogoUrl());
        conference.setContactEmail(form.getContactEmail());
        conference.setAboutHtml(blankToNull(form.getAboutHtml()));
        conference.setCallForPapersHtml(blankToNull(form.getCallForPapersHtml()));
        conference.setVenueAddress(blankToNull(form.getVenueAddress()));
        conference.setVenueMapEmbedUrl(form.getVenueMapEmbedUrl());
        conference.setTravelInfoHtml(blankToNull(form.getTravelInfoHtml()));

        ConferencePaymentConfig paymentConfig = new ConferencePaymentConfig();
        paymentConfig.setProvider(form.getPaymentProvider());

        if (form.getPaymentProvider() == PaymentProvider.STRIPE) {
            paymentConfig.setStripePublishableKey(form.getStripePublishableKey());
            paymentConfig.setStripeSecretKey(form.getStripeSecretKey());
        } else if (form.getPaymentProvider() == PaymentProvider.PAYPAL) {
            paymentConfig.setPaypalClientId(form.getPaypalClientId());
            paymentConfig.setPaypalClientSecret(form.getPaypalClientSecret());
        } else if (form.getPaymentProvider() == PaymentProvider.LOCAL_BANK) {
            paymentConfig.setBankDetails(form.getBankDetails());
        }

        paymentConfig.setConference(conference);
        conference.setPaymentConfig(paymentConfig);

        Conference saved = conferenceService.saveConference(conference);

        committeeService.assignChair(saved, chairUser);
        for (Long coChairId : form.getCoChairUserIds()) {
            User coChairUser = userRepository.findById(coChairId)
                    .orElseThrow(() -> new IllegalArgumentException("Selected Co-Chair not found"));
            committeeService.addRole(saved, coChairUser, CommitteeRole.CO_CHAIR, null);
        }

        if (form.getCloneFromConferenceId() != null) {
            cloneSubThemesAndNonChairRoles(form.getCloneFromConferenceId(), saved);
        }

        return "redirect:/admin/dashboard";
    }

    @GetMapping("/list")
    public String listConferences(Model model) {
        model.addAttribute("conferences", conferenceRepository.findAll());
        return "admin/conference_list";
    }

    @GetMapping("/{id}/edit")
    public String editConferenceForm(@PathVariable Long id, Model model) {
        Conference conference = conferenceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));

        ConferenceForm form = new ConferenceForm();
        form.setTitle(conference.getTitle());
        form.setVenue(conference.getVenue());
        form.setStartDate(conference.getStartDate());
        form.setEndDate(conference.getEndDate());
        form.setActive(conference.isActive());
        form.setBlindReview(conference.isBlindReview());
        form.setLogoUrl(conference.getLogoUrl());
        form.setContactEmail(conference.getContactEmail());
        form.setAboutHtml(conference.getAboutHtml());
        form.setCallForPapersHtml(conference.getCallForPapersHtml());
        form.setVenueAddress(conference.getVenueAddress());
        form.setVenueMapEmbedUrl(conference.getVenueMapEmbedUrl());
        form.setTravelInfoHtml(conference.getTravelInfoHtml());

        ConferenceCommitteeRole chairRole = committeeService.getCommitteeForConference(conference).stream()
                .filter(r -> r.getRole() == CommitteeRole.CHAIR)
                .findFirst().orElse(null);
        if (chairRole != null) {
            form.setChairUserId(chairRole.getUser().getId());
        }
        for (ConferenceCommitteeRole role : committeeService.getCommitteeForConference(conference)) {
            if (role.getRole() == CommitteeRole.CO_CHAIR) {
                form.getCoChairUserIds().add(role.getUser().getId());
            }
        }

        ConferencePaymentConfig paymentConfig = conference.getPaymentConfig();
        if (paymentConfig != null) {
            form.setPaymentProvider(paymentConfig.getProvider());
            form.setStripePublishableKey(paymentConfig.getStripePublishableKey());
            form.setStripeSecretKey(paymentConfig.getStripeSecretKey());
            form.setPaypalClientId(paymentConfig.getPaypalClientId());
            form.setPaypalClientSecret(paymentConfig.getPaypalClientSecret());
            form.setBankDetails(paymentConfig.getBankDetails());
        }

        model.addAttribute("conferenceForm", form);
        model.addAttribute("allUsers", userRepository.findAll());
        model.addAttribute("allConferences", conferenceRepository.findAll());
        model.addAttribute("editingConferenceId", id);
        return "admin/conference_form";
    }

    @PostMapping("/{id}/edit")
    public String updateConference(@PathVariable Long id, @ModelAttribute ConferenceForm form) {
        Conference conference = conferenceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));

        conference.setTitle(form.getTitle());
        conference.setVenue(form.getVenue());
        conference.setStartDate(form.getStartDate());
        conference.setEndDate(form.getEndDate());
        conference.setActive(form.isActive());
        conference.setBlindReview(form.isBlindReview());
        conference.setLogoUrl(form.getLogoUrl());
        conference.setContactEmail(form.getContactEmail());
        conference.setAboutHtml(blankToNull(form.getAboutHtml()));
        conference.setCallForPapersHtml(blankToNull(form.getCallForPapersHtml()));
        conference.setVenueAddress(blankToNull(form.getVenueAddress()));
        conference.setVenueMapEmbedUrl(form.getVenueMapEmbedUrl());
        conference.setTravelInfoHtml(blankToNull(form.getTravelInfoHtml()));

        ConferencePaymentConfig paymentConfig = conference.getPaymentConfig();
        if (paymentConfig == null) {
            paymentConfig = new ConferencePaymentConfig();
            paymentConfig.setConference(conference);
            conference.setPaymentConfig(paymentConfig);
        }
        paymentConfig.setProvider(form.getPaymentProvider());
        if (form.getPaymentProvider() == PaymentProvider.STRIPE) {
            paymentConfig.setStripePublishableKey(form.getStripePublishableKey());
            paymentConfig.setStripeSecretKey(form.getStripeSecretKey());
        } else if (form.getPaymentProvider() == PaymentProvider.PAYPAL) {
            paymentConfig.setPaypalClientId(form.getPaypalClientId());
            paymentConfig.setPaypalClientSecret(form.getPaypalClientSecret());
        } else if (form.getPaymentProvider() == PaymentProvider.LOCAL_BANK) {
            paymentConfig.setBankDetails(form.getBankDetails());
        }

        conferenceService.saveConference(conference);
        return "redirect:/admin/conference/list";
    }

    // Copies sub-themes and non-CHAIR/CO_CHAIR committee roles (reviewers, finance/registration/
    // proceedings managers) from the source conference onto the newly saved one -- these have no
    // form fields of their own to carry through the prefilled form, unlike chair/co-chairs and
    // payment config which round-trip through ConferenceForm.
    private void cloneSubThemesAndNonChairRoles(Long sourceId, Conference target) {
        Conference source = conferenceRepository.findById(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("Conference to clone from not found"));

        for (SubTheme sourceTheme : source.getSubThemes()) {
            SubTheme clonedTheme = new SubTheme();
            clonedTheme.setConference(target);
            clonedTheme.setName(sourceTheme.getName());
            clonedTheme.setDescription(sourceTheme.getDescription());
            target.getSubThemes().add(clonedTheme);
        }
        conferenceRepository.save(target);

        for (ConferenceCommitteeRole sourceRole : committeeService.getCommitteeForConference(source)) {
            if (sourceRole.getRole() == CommitteeRole.CHAIR || sourceRole.getRole() == CommitteeRole.CO_CHAIR) {
                continue;
            }
            committeeService.addRole(target, sourceRole.getUser(), sourceRole.getRole(), sourceRole.getDisplayTitle());
        }
    }

    // The public templates for these fields only show their "not published yet" fallback when
    // the field is null, not when it's blank -- an admin-authored content field left empty in
    // the form would otherwise save as "" and silently suppress that fallback message.
    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    @Data
    public static class ConferenceForm {
        private String title;
        private String venue;
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        private LocalDate startDate;
        @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
        private LocalDate endDate;
        private boolean active;
        private boolean blindReview;
        private String logoUrl;
        private String contactEmail;

        @NotNull
        private Long chairUserId;
        private List<Long> coChairUserIds = new ArrayList<>();

        // Payment
        private PaymentProvider paymentProvider = PaymentProvider.FREE;
        private String stripePublishableKey;
        private String stripeSecretKey;
        private String paypalClientId;
        private String paypalClientSecret;
        private String bankDetails;

        // Set when this form was prefilled via "Clone from previous conference" -- carries the
        // source conference's id through to saveConference so sub-themes and non-chair committee
        // roles (which have no dedicated form fields) can be copied at save time too.
        private Long cloneFromConferenceId;

        // Public-site content (Task 1's new Conference fields)
        private String aboutHtml;
        private String callForPapersHtml;
        private String venueAddress;
        private String venueMapEmbedUrl;
        private String travelInfoHtml;
    }
}
