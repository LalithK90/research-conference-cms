package org.confcms.cms.branding;

import org.confcms.cms.service.SafeUrlValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// Global, install-wide branding (favicon/logo/colors) -- not per-conference. This app supports
// multiple conferences in one install, but is sold as one branded instance per customer.
@Controller
@RequestMapping("/admin/branding")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminBrandingController {

    private final AppBrandingService brandingService;

    @GetMapping
    public String form(Model model) {
        model.addAttribute("branding", brandingService.get());
        return "admin/branding";
    }

    @PostMapping("/save")
    public String save(@ModelAttribute AppBranding branding, RedirectAttributes redirectAttributes) {
        if (!SafeUrlValidator.isHttpOrHttpsOrBlank(branding.getFaviconUrl())
                || !SafeUrlValidator.isHttpOrHttpsOrBlank(branding.getLogoUrl())) {
            redirectAttributes.addFlashAttribute("error", "Favicon and logo URLs must start with http:// or https://");
            return "redirect:/admin/branding";
        }
        brandingService.save(branding);
        redirectAttributes.addFlashAttribute("message", "Branding updated.");
        return "redirect:/admin/branding";
    }
}
