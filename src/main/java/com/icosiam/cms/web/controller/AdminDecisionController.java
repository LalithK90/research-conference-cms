package com.icosiam.cms.web.controller;

import com.icosiam.cms.service.DecisionService;
import com.icosiam.cms.submission.domain.Paper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/decisions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminDecisionController {

    private final DecisionService decisionService;

    @GetMapping("/suggestions")
    public ResponseEntity<?> suggestions(@RequestParam(defaultValue = "3.5") double acceptThreshold,
                                         @RequestParam(defaultValue = "2.5") double rejectThreshold) {
        return ResponseEntity.ok(decisionService.suggestDecisions(acceptThreshold, rejectThreshold));
    }

    @PostMapping("/{paperId}/apply")
    public ResponseEntity<?> applyDecision(@PathVariable Long paperId, @RequestParam String decision) {
        Paper p = decisionService.applyDecision(paperId, decision);
        return ResponseEntity.ok(p);
    }

    @PostMapping("/apply/bulk")
    public ResponseEntity<?> bulkApply(@RequestBody List<Long> paperIds, @RequestParam String decision) {
        List<Paper> updated = decisionService.applyBulkDecision(paperIds, decision);
        return ResponseEntity.ok(updated);
    }
}
