package com.icosiam.cms.web.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icosiam.cms.repository.UserRepository;
import com.icosiam.cms.domain.User;
import com.icosiam.cms.submission.domain.Paper;
import com.icosiam.cms.submission.domain.PaperAuthor;
import com.icosiam.cms.submission.service.SubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/submission")
@RequiredArgsConstructor
public class SubmissionRestController {

    private final SubmissionService submissionService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<?> submitPaper(@RequestParam String title,
                                         @RequestParam("abstract") String abstractText,
                                         @RequestParam String track,
                                         @RequestParam(value = "authors", required = false) String authorsJson,
                                         @RequestParam("file") MultipartFile file) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));

        List<PaperAuthor> authors = Collections.emptyList();
        try {
            if (authorsJson != null && !authorsJson.isBlank()) {
                authors = objectMapper.readValue(authorsJson, new TypeReference<List<PaperAuthor>>(){});
            }
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Invalid authors JSON");
        }

        Paper saved = submissionService.submitPaper(user, title, abstractText, track, file, authors);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/my")
    public ResponseEntity<?> mySubmissions() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
        return ResponseEntity.ok(submissionService.getPapersBySubmitter(user));
    }

    @PostMapping(path = "/{id}/version", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadVersion(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
        try {
            Paper saved = submissionService.uploadNewVersion(user, id, file);
            return ResponseEntity.ok(saved);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @PostMapping("/{id}/withdraw")
    public ResponseEntity<?> withdraw(@PathVariable Long id) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
        try {
            Paper saved = submissionService.withdrawPaper(user, id);
            return ResponseEntity.ok(saved);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }
}
