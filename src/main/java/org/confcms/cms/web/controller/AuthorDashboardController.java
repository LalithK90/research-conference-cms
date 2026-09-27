package org.confcms.cms.web.controller;

import org.confcms.cms.domain.User;
import org.confcms.cms.registration.repository.RegistrationRepository;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.submission.service.SubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

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

    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
