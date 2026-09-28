package org.confcms.cms.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.Sponsor;
import org.confcms.cms.domain.SponsorTier;
import org.confcms.cms.repository.ConferenceRepository;
import org.confcms.cms.repository.SponsorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/conference/{conferenceId}/sponsors")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSponsorController {

    private final SponsorRepository sponsorRepository;
    private final ConferenceRepository conferenceRepository;

    @GetMapping
    public String list(@PathVariable Long conferenceId, Model model) {
        Conference conference = loadConference(conferenceId);
        model.addAttribute("conference", conference);
        model.addAttribute("sponsors", sponsorRepository.findByConferenceIdOrderByDisplayOrderAsc(conferenceId));
        return "admin/sponsors";
    }

    @GetMapping("/new")
    public String newForm(@PathVariable Long conferenceId, Model model) {
        model.addAttribute("conference", loadConference(conferenceId));
        model.addAttribute("sponsor", new Sponsor());
        model.addAttribute("tiers", SponsorTier.values());
        return "admin/sponsor_form";
    }

    @PostMapping("/save")
    public String save(@PathVariable Long conferenceId, @ModelAttribute Sponsor sponsor) {
        sponsor.setConference(loadConference(conferenceId));
        sponsorRepository.save(sponsor);
        return "redirect:/admin/conference/" + conferenceId + "/sponsors";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long conferenceId, @PathVariable Long id, Model model) {
        Sponsor sponsor = sponsorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sponsor not found"));
        model.addAttribute("conference", loadConference(conferenceId));
        model.addAttribute("sponsor", sponsor);
        model.addAttribute("tiers", SponsorTier.values());
        return "admin/sponsor_form";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long conferenceId, @PathVariable Long id) {
        sponsorRepository.deleteById(id);
        return "redirect:/admin/conference/" + conferenceId + "/sponsors";
    }

    private Conference loadConference(Long conferenceId) {
        return conferenceRepository.findById(conferenceId)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));
    }
}
