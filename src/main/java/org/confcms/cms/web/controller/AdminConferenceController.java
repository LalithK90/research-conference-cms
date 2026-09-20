package org.confcms.cms.web.controller;

import org.confcms.cms.domain.CommitteeRole;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.ConferencePaymentConfig;
import org.confcms.cms.domain.PaymentProvider;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

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

    @GetMapping("/new")
    public String newConferenceForm(Model model) {
        model.addAttribute("conferenceForm", new ConferenceForm());
        model.addAttribute("allUsers", userRepository.findAll());
        return "admin/conference_form";
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

        return "redirect:/admin/dashboard";
    }

    @Data
    public static class ConferenceForm {
        private String title;
        private String venue;
        private LocalDate startDate;
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
    }
}
