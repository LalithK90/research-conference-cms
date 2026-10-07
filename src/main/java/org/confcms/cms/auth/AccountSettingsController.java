package org.confcms.cms.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.confcms.cms.user.User;
import org.confcms.cms.useridentity.UserIdentity;
import org.confcms.cms.useridentity.UserIdentityRepository;
import org.confcms.cms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class AccountSettingsController {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;

    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }

    @GetMapping("/account")
    public String show(HttpServletRequest request, Model model) {
        User user = actingUser();
        model.addAttribute("user", user);
        model.addAttribute("identities", userIdentityRepository.findByUserId(user.getId()));
        var session = request.getSession(false);
        model.addAttribute("passwordPromptPending", session != null && session.getAttribute("passwordPromptPending") != null);
        return "account_settings";
    }

    // POST, not GET: dismissal mutates session state, and a state-changing GET is
    // link-prefetchable -- a browser or proxy prefetching the href could silently
    // dismiss the prompt without the user ever clicking it.
    @PostMapping("/account/dismiss-password-prompt")
    public String dismissPasswordPrompt(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.removeAttribute("passwordPromptPending");
        }
        return "redirect:/account";
    }

    // Refuses to unlink the user's only remaining login method -- a passwordless (OAuth2-only)
    // account with a single linked identity has no other way back in. Not a concern for a user
    // with a password set, or one with more than one identity linked.
    @PostMapping("/account/identities/{provider}/unlink")
    public String unlinkIdentity(@PathVariable String provider, RedirectAttributes redirectAttributes) {
        User user = actingUser();
        List<UserIdentity> identities = userIdentityRepository.findByUserId(user.getId());
        boolean hasPassword = user.getPasswordHash() != null && !user.getPasswordHash().isBlank();

        if (!hasPassword && identities.size() <= 1) {
            redirectAttributes.addFlashAttribute("error",
                    "Can't unlink your only sign-in method. Set a password first, or link another account.");
            return "redirect:/account";
        }

        userIdentityRepository.findByUserIdAndProvider(user.getId(), provider)
                .ifPresent(userIdentityRepository::delete);
        redirectAttributes.addFlashAttribute("message", "Account unlinked.");
        return "redirect:/account";
    }
}
