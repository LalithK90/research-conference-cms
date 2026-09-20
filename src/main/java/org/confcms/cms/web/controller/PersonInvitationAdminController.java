package org.confcms.cms.web.controller;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.InvitationPurpose;
import org.confcms.cms.domain.InvitationStatus;
import org.confcms.cms.domain.PersonInvitation;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.ConferenceRepository;
import org.confcms.cms.repository.PersonInvitationRepository;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.PersonInvitationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/admin/invitations")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
public class PersonInvitationAdminController {

    private final PersonInvitationService personInvitationService;
    private final PersonInvitationRepository personInvitationRepository;
    private final UserRepository userRepository;
    private final CommitteeService committeeService;
    private final ConferenceRepository conferenceRepository;

    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }

    // Deliberately omits the token field: even an authorized chair viewing this list
    // shouldn't have the live acceptance token surfaced through the UI/logs -- only
    // the invitee's emailed link should ever carry it.
    public record InvitationSummary(Long id, String name, String email, InvitationPurpose purpose,
                                     InvitationStatus status, int resendCount, LocalDateTime expiresAt) {
        static InvitationSummary from(PersonInvitation invitation) {
            return new InvitationSummary(invitation.getId(), invitation.getName(), invitation.getEmail(),
                    invitation.getPurpose(), invitation.getStatus(), invitation.getResendCount(), invitation.getExpiresAt());
        }
    }

    @GetMapping("/conference/{conferenceId}")
    public ResponseEntity<?> listForConference(@PathVariable Long conferenceId) {
        Conference conference = conferenceRepository.findById(conferenceId)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));

        User user = actingUser();
        boolean isAdmin = user.getRole() == Role.ADMIN;
        if (!isAdmin && !committeeService.isChairOrCoChair(user, conference)) {
            return ResponseEntity.status(403).body("Not authorized to view invitations for this conference");
        }

        List<InvitationSummary> invitations = personInvitationRepository.findByConferenceId(conferenceId)
                .stream().map(InvitationSummary::from).toList();
        return ResponseEntity.ok(invitations);
    }

    @PostMapping("/{invitationId}/approve")
    public ResponseEntity<?> approve(@PathVariable Long invitationId) {
        try {
            return ResponseEntity.ok(personInvitationService.approve(actingUser(), invitationId));
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @PostMapping("/{invitationId}/reject")
    public ResponseEntity<?> reject(@PathVariable Long invitationId, @RequestParam(required = false) String reason) {
        try {
            return ResponseEntity.ok(personInvitationService.reject(actingUser(), invitationId, reason));
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @PostMapping("/{invitationId}/resend")
    public ResponseEntity<?> resend(@PathVariable Long invitationId) {
        try {
            return ResponseEntity.ok(personInvitationService.resend(actingUser(), invitationId));
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        } catch (IllegalStateException ise) {
            return ResponseEntity.status(429).body(ise.getMessage());
        }
    }

    @PostMapping("/recruit")
    public ResponseEntity<?> recruitReviewer(@RequestParam Long conferenceId, @RequestParam String name, @RequestParam String email) {
        Conference conference = new Conference();
        conference.setId(conferenceId);
        try {
            return ResponseEntity.ok(personInvitationService.recruitReviewer(actingUser(), conference, name, email));
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }
}
