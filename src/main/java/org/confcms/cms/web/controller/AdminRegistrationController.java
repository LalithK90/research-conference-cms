package org.confcms.cms.web.controller;

import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.domain.Registration;
import org.confcms.cms.registration.repository.RegistrationRepository;
import org.confcms.cms.registration.service.RegistrationService;
import org.confcms.cms.conference.ConferenceService;
import org.confcms.cms.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Controller
@RequestMapping("/admin/registrations")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminRegistrationController {

    private final RegistrationRepository registrationRepository;
    private final RegistrationService registrationService;
    private final ConferenceService conferenceService;
    private final FileStorageService fileStorageService;

    @GetMapping
    public String list(Model model) {
        var conference = conferenceService.getActiveConference();
        model.addAttribute("registrations", registrationRepository
                .findByConferenceIdAndPaymentStatus(conference.getId(), PaymentStatus.AWAITING_VERIFICATION));
        return "admin/registrations";
    }

    @GetMapping("/{id}/slip")
    public ResponseEntity<?> viewSlip(@PathVariable Long id) {
        Registration registration = registrationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Registration not found"));
        if (registration.getBankSlipPath() == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            Path path = fileStorageService.load(registration.getBankSlipPath());
            Resource resource = new UrlResource(path.toUri());
            String contentDisposition = ContentDisposition.inline()
                    .filename(registration.getBankSlipOriginalFilename(), StandardCharsets.UTF_8)
                    .build()
                    .toString();
            return ResponseEntity.ok()
                    .contentType(contentTypeFor(path))
                    .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                    .body(resource);
        } catch (MalformedURLException e) {
            return ResponseEntity.status(404).body("File not found");
        }
    }

    private MediaType contentTypeFor(Path path) {
        try {
            byte[] header = Files.readAllBytes(path);
            if (header.length >= 3 && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8) {
                return MediaType.IMAGE_JPEG;
            }
            if (header.length >= 4 && header[0] == (byte) 0x89 && header[1] == 'P') {
                return MediaType.IMAGE_PNG;
            }
            return MediaType.APPLICATION_PDF;
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id) {
        registrationService.markAsPaid(id);
        return "redirect:/admin/registrations";
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id, @RequestParam(required = false) String reason) {
        registrationService.reject(id, reason);
        return "redirect:/admin/registrations";
    }
}
