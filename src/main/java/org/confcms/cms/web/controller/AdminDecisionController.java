package org.confcms.cms.web.controller;

import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.DecisionService;
import org.confcms.cms.submission.domain.Paper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/decisions")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
public class AdminDecisionController {

    private final DecisionService decisionService;
    private final UserRepository userRepository;

    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }

    @GetMapping("/suggestions")
    public ResponseEntity<?> suggestions(@RequestParam(defaultValue = "3.5") double acceptThreshold,
                                         @RequestParam(defaultValue = "2.5") double rejectThreshold) {
        return ResponseEntity.ok(decisionService.suggestDecisions(acceptThreshold, rejectThreshold));
    }

    @PostMapping("/{paperId}/apply")
    public ResponseEntity<?> applyDecision(@PathVariable Long paperId, @RequestParam String decision) {
        try {
            Paper p = decisionService.applyDecision(actingUser(), paperId, decision);
            return ResponseEntity.ok(p);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @PostMapping("/apply/bulk")
    public ResponseEntity<?> bulkApply(@RequestBody List<Long> paperIds, @RequestParam String decision) {
        try {
            List<Paper> updated = decisionService.applyBulkDecision(actingUser(), paperIds, decision);
            return ResponseEntity.ok(updated);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @PostMapping("/{paperId}/desk-review")
    public ResponseEntity<?> deskReview(@PathVariable Long paperId, @RequestParam DecisionService.DeskDecision decision) {
        try {
            Paper p = decisionService.deskReview(actingUser(), paperId, decision);
            return ResponseEntity.ok(p);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }
}
