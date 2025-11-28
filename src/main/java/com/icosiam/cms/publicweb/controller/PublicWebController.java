package com.icosiam.cms.publicweb.controller;

import com.icosiam.cms.domain.Conference;
import com.icosiam.cms.service.ConferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

@Controller("publicWebController")
@RequiredArgsConstructor
public class PublicWebController {

    private final ConferenceService conferenceService;

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
}
