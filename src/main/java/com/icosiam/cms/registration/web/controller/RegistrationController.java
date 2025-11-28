package com.icosiam.cms.registration.web.controller;

import com.icosiam.cms.domain.Conference;
import com.icosiam.cms.domain.ConferencePaymentConfig;
import com.icosiam.cms.domain.User;
import com.icosiam.cms.registration.service.RegistrationService;
import com.icosiam.cms.service.ConferenceService;
import com.icosiam.cms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

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
                                      @RequestParam(defaultValue = "0") BigDecimal amount,
                                      @AuthenticationPrincipal UserDetails userDetails) {
        
        if (userDetails == null) {
            return "redirect:/login";
        }

        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        registrationService.register(user, ticketType, amount);

        return "redirect:/dashboard?registered=true";
    }
}
