package org.confcms.cms.speaker;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.conference.ConferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/conference/{conferenceId}/speakers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSpeakerController {

    private final SpeakerRepository speakerRepository;
    private final ConferenceRepository conferenceRepository;

    @GetMapping
    public String list(@PathVariable Long conferenceId, Model model) {
        Conference conference = loadConference(conferenceId);
        model.addAttribute("conference", conference);
        model.addAttribute("speakers", speakerRepository.findByConferenceIdOrderByDisplayOrderAsc(conferenceId));
        return "admin/speakers";
    }

    @GetMapping("/new")
    public String newForm(@PathVariable Long conferenceId, Model model) {
        model.addAttribute("conference", loadConference(conferenceId));
        model.addAttribute("speaker", new Speaker());
        model.addAttribute("types", SpeakerType.values());
        return "admin/speaker_form";
    }

    @PostMapping("/save")
    public String save(@PathVariable Long conferenceId, @ModelAttribute Speaker speaker) {
        if (speaker.getId() != null) {
            loadSpeakerInConference(conferenceId, speaker.getId());
        }
        speaker.setConference(loadConference(conferenceId));
        speakerRepository.save(speaker);
        return "redirect:/admin/conference/" + conferenceId + "/speakers";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long conferenceId, @PathVariable Long id, Model model) {
        Speaker speaker = loadSpeakerInConference(conferenceId, id);
        model.addAttribute("conference", loadConference(conferenceId));
        model.addAttribute("speaker", speaker);
        model.addAttribute("types", SpeakerType.values());
        return "admin/speaker_form";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long conferenceId, @PathVariable Long id) {
        loadSpeakerInConference(conferenceId, id);
        speakerRepository.deleteById(id);
        return "redirect:/admin/conference/" + conferenceId + "/speakers";
    }

    private Conference loadConference(Long conferenceId) {
        return conferenceRepository.findById(conferenceId)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));
    }

    // Confirms the speaker id in the URL actually belongs to the conference id also in the URL --
    // without this, a speaker id from a different conference could be edited/deleted through this
    // conference's URL, silently mixing conference-scoped data.
    private Speaker loadSpeakerInConference(Long conferenceId, Long id) {
        Speaker speaker = speakerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Speaker not found"));
        if (!speaker.getConference().getId().equals(conferenceId)) {
            throw new IllegalArgumentException("Speaker does not belong to this conference");
        }
        return speaker;
    }
}
