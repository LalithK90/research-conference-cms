package org.confcms.cms.sponsor;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.repository.ConferenceRepository;
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
        if (sponsor.getId() != null) {
            loadSponsorInConference(conferenceId, sponsor.getId());
        }
        sponsor.setConference(loadConference(conferenceId));
        sponsorRepository.save(sponsor);
        return "redirect:/admin/conference/" + conferenceId + "/sponsors";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long conferenceId, @PathVariable Long id, Model model) {
        Sponsor sponsor = loadSponsorInConference(conferenceId, id);
        model.addAttribute("conference", loadConference(conferenceId));
        model.addAttribute("sponsor", sponsor);
        model.addAttribute("tiers", SponsorTier.values());
        return "admin/sponsor_form";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long conferenceId, @PathVariable Long id) {
        loadSponsorInConference(conferenceId, id);
        sponsorRepository.deleteById(id);
        return "redirect:/admin/conference/" + conferenceId + "/sponsors";
    }

    private Conference loadConference(Long conferenceId) {
        return conferenceRepository.findById(conferenceId)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));
    }

    // Confirms the sponsor id in the URL actually belongs to the conference id also in the URL --
    // without this, a sponsor id from a different conference could be edited/deleted through this
    // conference's URL, silently mixing conference-scoped data.
    private Sponsor loadSponsorInConference(Long conferenceId, Long id) {
        Sponsor sponsor = sponsorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sponsor not found"));
        if (!sponsor.getConference().getId().equals(conferenceId)) {
            throw new IllegalArgumentException("Sponsor does not belong to this conference");
        }
        return sponsor;
    }
}
