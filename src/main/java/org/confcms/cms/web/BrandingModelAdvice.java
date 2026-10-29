package org.confcms.cms.web;

import org.confcms.cms.branding.AppBranding;
import org.confcms.cms.branding.AppBrandingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

// Makes install-wide branding (favicon/logo/colors) available as ${branding} on every page --
// public, admin, and auth alike -- unlike ConferenceService.getActiveConference(), which is
// only injected per-controller (PublicWebController's own @ModelAttribute) and never reaches
// admin/login pages. A @ControllerAdvice is the one mechanism that reaches all three.
@ControllerAdvice
@RequiredArgsConstructor
public class BrandingModelAdvice {

    private final AppBrandingService brandingService;

    @ModelAttribute("branding")
    public AppBranding branding() {
        return brandingService.get();
    }
}
