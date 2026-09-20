package org.confcms.cms.web.controller;

import org.confcms.cms.domain.PersonInvitation;
import org.confcms.cms.repository.PersonInvitationRepository;
import org.confcms.cms.service.PersonInvitationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/invitations")
@RequiredArgsConstructor
public class PersonInvitationController {

    private final PersonInvitationService personInvitationService;
    private final PersonInvitationRepository personInvitationRepository;

    @GetMapping("/accept")
    public String showAcceptForm(@RequestParam String token, Model model) {
        PersonInvitation invitation = personInvitationRepository.findByToken(token).orElse(null);
        if (invitation == null || invitation.isUsed() || invitation.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
            model.addAttribute("error", "This invitation link is invalid or has expired.");
            return "invitations/accept";
        }
        model.addAttribute("token", token);
        model.addAttribute("invitation", invitation);
        return "invitations/accept";
    }

    @PostMapping("/accept")
    public String processAccept(@RequestParam String token, @RequestParam String password, Model model) {
        try {
            personInvitationService.acceptInvitation(token, password);
            return "redirect:/login?invitationAccepted=true";
        } catch (IllegalArgumentException iae) {
            model.addAttribute("error", iae.getMessage());
            model.addAttribute("token", token);
            return "invitations/accept";
        }
    }
}
