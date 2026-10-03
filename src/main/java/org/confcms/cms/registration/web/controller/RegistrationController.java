package org.confcms.cms.registration.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.user.User;
import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.domain.Registration;
import org.confcms.cms.registration.service.RegistrationService;
import org.confcms.cms.service.ConferenceService;
import org.confcms.cms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@Controller
@RequiredArgsConstructor
public class RegistrationController {

    private final ConferenceService conferenceService;
    private final RegistrationService registrationService;
    private final UserRepository userRepository;

    @GetMapping("/register")
    public String showRegistrationPage(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        Conference conference = conferenceService.getActiveConference();
        model.addAttribute("conference", conference);

        if (conference.getPaymentConfig() != null) {
            model.addAttribute("paymentConfig", conference.getPaymentConfig());
        }

        if (userDetails != null) {
            User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);
            model.addAttribute("user", user);
        }

        return "public/register";
    }

    @PostMapping("/register")
    public String processRegistration(@RequestParam String ticketType,
                                      @RequestParam(value = "bankSlip", required = false) MultipartFile bankSlip,
                                      @AuthenticationPrincipal UserDetails userDetails,
                                      Model model) {

        if (userDetails == null) {
            return "redirect:/login";
        }

        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Conference conference = conferenceService.getActiveConference();

        try {
            registrationService.register(user, conference, ticketType, bankSlip);
            return "redirect:/dashboard?registered=true";
        } catch (IllegalArgumentException iae) {
            model.addAttribute("conference", conference);
            model.addAttribute("paymentConfig", conference.getPaymentConfig());
            model.addAttribute("user", user);
            model.addAttribute("error", iae.getMessage());
            return "public/register";
        }
    }

    @GetMapping("/registration/{id}/reupload-slip")
    public String showReuploadForm(@PathVariable Long id, Model model, @AuthenticationPrincipal UserDetails userDetails) {
        Registration registration = ownedRejectedRegistration(id, userDetails);
        model.addAttribute("registration", registration);
        return "reupload_slip";
    }

    @PostMapping("/registration/{id}/reupload-slip")
    public String reuploadSlip(@PathVariable Long id, @RequestParam MultipartFile bankSlip,
                                Model model, @AuthenticationPrincipal UserDetails userDetails) {
        Registration registration = ownedRejectedRegistration(id, userDetails);
        try {
            registrationService.reuploadSlip(registration, bankSlip);
            return "redirect:/dashboard?slipResubmitted=true";
        } catch (IllegalArgumentException iae) {
            model.addAttribute("registration", registration);
            model.addAttribute("error", iae.getMessage());
            return "reupload_slip";
        }
    }

    private Registration ownedRejectedRegistration(Long id, UserDetails userDetails) {
        if (userDetails == null) {
            throw new IllegalArgumentException("Not authenticated");
        }
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Registration registration = registrationService.getRegistration(id);
        if (!registration.getUser().getId().equals(user.getId())) {
            throw new SecurityException("Not your registration");
        }
        if (registration.getPaymentStatus() != PaymentStatus.FAILED) {
            throw new IllegalArgumentException("This registration is not awaiting a re-upload");
        }
        return registration;
    }
}
