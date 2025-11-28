package com.icosiam.cms.controller;

import com.icosiam.cms.service.ConferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class PublicController {

    private final ConferenceService conferenceService;

    @GetMapping({"/", "/home"})
    public String home(Model model) {
        model.addAttribute("conference", conferenceService.getActiveConference());
        return "public/home";
    }

    @GetMapping("/about")
    public String about(Model model) {
        model.addAttribute("conference", conferenceService.getActiveConference());
        return "public/about";
    }

    @GetMapping("/committee")
    public String committee(Model model) {
        var conference = conferenceService.getActiveConference();
        model.addAttribute("conference", conference);
        model.addAttribute("committee", conference.getSteeringCommittee());
        return "public/committee";
    }

    @GetMapping("/speakers")
    public String speakers(Model model) {
        model.addAttribute("conference", conferenceService.getActiveConference());
        return "public/speakers";
    }

    @GetMapping("/schedule")
    public String schedule(Model model) {
        model.addAttribute("conference", conferenceService.getActiveConference());
        return "public/schedule";
    }

    @GetMapping("/venue")
    public String venue(Model model) {
        model.addAttribute("conference", conferenceService.getActiveConference());
        return "public/venue";
    }

    @GetMapping("/contact")
    public String contact(Model model) {
        model.addAttribute("conference", conferenceService.getActiveConference());
        return "public/contact";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
