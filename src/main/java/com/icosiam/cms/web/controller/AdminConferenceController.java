package com.icosiam.cms.web.controller;

import com.icosiam.cms.domain.Conference;
import com.icosiam.cms.domain.ConferencePaymentConfig;
import com.icosiam.cms.domain.PaymentProvider;
import com.icosiam.cms.service.ConferenceService;
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

@Controller
@RequestMapping("/admin/conference")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminConferenceController {

    private final ConferenceService conferenceService;

    @GetMapping("/new")
    public String newConferenceForm(Model model) {
        model.addAttribute("conferenceForm", new ConferenceForm());
        return "admin/conference_form";
    }

    @PostMapping("/save")
    public String saveConference(@ModelAttribute ConferenceForm form) {
        Conference conference = new Conference();
        conference.setTitle(form.getTitle());
        conference.setVenue(form.getVenue());
        conference.setStartDate(form.getStartDate());
        conference.setEndDate(form.getEndDate());
        conference.setActive(form.isActive());
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

        conferenceService.saveConference(conference);

        return "redirect:/admin/dashboard";
    }

    @Data
    public static class ConferenceForm {
        private String title;
        private String venue;
        private LocalDate startDate;
        private LocalDate endDate;
        private boolean active;
        private String logoUrl;
        private String contactEmail;

        // Payment
        private PaymentProvider paymentProvider = PaymentProvider.FREE;
        private String stripePublishableKey;
        private String stripeSecretKey;
        private String paypalClientId;
        private String paypalClientSecret;
        private String bankDetails;
    }
}
