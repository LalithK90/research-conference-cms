package org.confcms.cms.web.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.domain.User;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperAuthor;
import org.confcms.cms.submission.dto.AuthorRequestDto;
import org.confcms.cms.submission.service.SubmissionService;
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
                List<AuthorRequestDto> authorDtos = objectMapper.readValue(authorsJson, new TypeReference<List<AuthorRequestDto>>(){});
                authors = authorDtos.stream().map(dto -> {
                    PaperAuthor author = new PaperAuthor();
                    author.setFullName(dto.getFullName());
                    author.setEmail(dto.getEmail());
                    author.setAffiliation(dto.getAffiliation());
                    author.setPresenter(dto.isPresenter());
                    return author;
                }).toList();
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
        } catch (IllegalStateException ise) {
            return ResponseEntity.status(409).body(ise.getMessage());
        }
    }

    @PostMapping(path = "/{id}/revision", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadRevision(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
        try {
            Paper saved = submissionService.uploadRevision(user, id, file);
            return ResponseEntity.ok(saved);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        } catch (IllegalStateException ise) {
            return ResponseEntity.status(409).body(ise.getMessage());
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
