package org.confcms.cms.web.controller;

import org.confcms.cms.user.User;
import org.confcms.cms.registration.repository.RegistrationRepository;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.submission.service.SubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class AuthorDashboardController {

    private final SubmissionService submissionService;
    private final RegistrationRepository registrationRepository;
    private final UserRepository userRepository;

    @GetMapping("/author/submissions")
    public String mySubmissions(Model model) {
        User user = actingUser();

        Map<Long, org.confcms.cms.registration.domain.Registration> registrationsByConference =
                registrationRepository.findByUserId(user.getId()).stream()
                        .collect(Collectors.toMap(r -> r.getConference().getId(), r -> r, (a, b) -> a));

        model.addAttribute("papers", submissionService.getPapersBySubmitter(user));
        model.addAttribute("registrationsByConference", registrationsByConference);
        return "author/submissions";
    }

    @PostMapping("/author/submissions/{id}/camera-ready")
    public String uploadCameraReady(@PathVariable Long id,
                                     @RequestParam("file") MultipartFile file,
                                     @RequestParam boolean copyrightAgreed,
                                     RedirectAttributes redirectAttributes) {
        try {
            submissionService.uploadCameraReady(actingUser(), id, file, copyrightAgreed);
            redirectAttributes.addFlashAttribute("message", "Camera-ready version submitted.");
        } catch (SecurityException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/author/submissions";
    }

    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
