package org.confcms.cms.publicweb.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.Speaker;
import org.confcms.cms.domain.SpeakerType;
import org.confcms.cms.domain.Sponsor;
import org.confcms.cms.domain.SponsorTier;
import org.confcms.cms.repository.ConferenceRepository;
import org.confcms.cms.repository.SpeakerRepository;
import org.confcms.cms.repository.SponsorRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.ConferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Controller("publicWebController")
@RequiredArgsConstructor
public class PublicWebController {

    private final ConferenceService conferenceService;
    private final CommitteeService committeeService;
    private final ClientRegistrationRepository clientRegistrationRepository;
    private final ConferenceRepository conferenceRepository;
    private final SpeakerRepository speakerRepository;
    private final SponsorRepository sponsorRepository;

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
        Conference activeConference = conferenceService.getActiveConference();
        List<Speaker> speakers = speakerRepository.findByConferenceIdOrderByDisplayOrderAsc(activeConference.getId());
        model.addAttribute("plenarySpeakers", speakers.stream()
                .filter(s -> s.getType() == SpeakerType.PLENARY).toList());
        model.addAttribute("keynoteSpeakers", speakers.stream()
                .filter(s -> s.getType() == SpeakerType.KEYNOTE).toList());
        return "public/speakers";
    }

    @GetMapping("/sponsors")
    public String sponsors(Model model) {
        Conference activeConference = conferenceService.getActiveConference();
        List<Sponsor> sponsors = sponsorRepository.findByConferenceIdOrderByDisplayOrderAsc(activeConference.getId());
        Map<SponsorTier, List<Sponsor>> byTier = new EnumMap<>(SponsorTier.class);
        for (SponsorTier tier : SponsorTier.values()) {
            byTier.put(tier, sponsors.stream().filter(s -> s.getTier() == tier).toList());
        }
        model.addAttribute("sponsorsByTier", byTier);
        model.addAttribute("hasNoSponsors", sponsors.isEmpty());
        return "public/sponsors";
    }

    @GetMapping("/venue")
    public String venue(Model model) {
        return "public/venue";
    }

    @GetMapping("/call-for-papers")
    public String callForPapers(Model model) {
        return "public/call_for_papers";
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
