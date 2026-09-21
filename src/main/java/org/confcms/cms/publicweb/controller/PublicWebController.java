package org.confcms.cms.publicweb.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.ConferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

@Controller("publicWebController")
@RequiredArgsConstructor
public class PublicWebController {

    private final ConferenceService conferenceService;
    private final CommitteeService committeeService;
    private final ClientRegistrationRepository clientRegistrationRepository;

    @ModelAttribute("conference")
    public Conference addConferenceToModel() {
        try {
            return conferenceService.getActiveConference();
        } catch (Exception e) {
            return null; // Handle case where no conference is active
        }
    }

    @GetMapping("/")
    public String home(Model model) {
        return "public/home";
    }

    @GetMapping("/home")
    public String homeAlias(Model model) {
        return "public/home";
    }

    @GetMapping("/about")
    public String about(Model model) {
        return "public/about";
    }

    @GetMapping("/committee")
    public String committee(Model model) {
        Conference activeConference = conferenceService.getActiveConference();
        model.addAttribute("committee", committeeService.getCommitteeForConference(activeConference));
        return "public/committee";
    }

    @GetMapping("/speakers")
    public String speakers(Model model) {
        return "public/speakers";
    }

    @GetMapping("/schedule")
    public String schedule(Model model) {
        return "public/schedule";
    }

    @GetMapping("/venue")
    public String venue(Model model) {
        return "public/venue";
    }

    @GetMapping("/contact")
    public String contact(Model model) {
        return "public/contact";
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("googleEnabled", clientRegistrationRepository.findByRegistrationId("google") != null);
        model.addAttribute("orcidEnabled", clientRegistrationRepository.findByRegistrationId("orcid") != null);
        return "login";
    }
}
