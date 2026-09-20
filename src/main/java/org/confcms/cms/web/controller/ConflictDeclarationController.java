package org.confcms.cms.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.ConflictDeclarationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/review/conflicts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('REVIEWER')")
public class ConflictDeclarationController {

    private final ConflictDeclarationService conflictDeclarationService;
    private final UserRepository userRepository;

    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }

    @PostMapping("/declare")
    public ResponseEntity<?> declare(@RequestParam Long conferenceId, @RequestParam Long declaredAgainstUserId) {
        Conference conference = new Conference();
        conference.setId(conferenceId);
        User declaredAgainstUser = userRepository.findById(declaredAgainstUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return ResponseEntity.ok(conflictDeclarationService.declareConflict(actingUser(), conference, declaredAgainstUser));
    }
}
