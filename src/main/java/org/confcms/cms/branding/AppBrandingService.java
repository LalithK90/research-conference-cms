package org.confcms.cms.branding;

import org.confcms.cms.service.HexColorValidator;
import org.confcms.cms.service.SafeUrlValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AppBrandingService {

    private final AppBrandingRepository repository;

    // Single global row: findAll().stream().findFirst() rather than a fixed id=1 lookup, so the
    // very first call (no row exists yet) and every later call share the same path -- no
    // separate "does row 1 exist" bootstrap step needed anywhere else in the app.
    @Transactional
    public AppBranding get() {
        return repository.findAll().stream().findFirst()
                .orElseGet(() -> repository.save(new AppBranding()));
    }

    // Validated here too, not just in AdminBrandingController -- this is the actual trust
    // boundary (a future second caller shouldn't be able to skip it), not the one controller
    // that happens to call it today. These fields render straight into a <style>/<link> tag in
    // every page's <head>, so a bad scheme or a value that breaks out of the CSS declaration
    // isn't just malformed data, it's live markup on every page in the app.
    //
    // Blank fields are stored as null, not "" -- templates check faviconUrl != null to decide
    // whether to fall back to the built-in default, and an empty string would pass that check
    // and render an empty href/src instead of falling back.
    @Transactional
    public AppBranding save(AppBranding form) {
        if (!SafeUrlValidator.isHttpOrHttpsOrBlank(form.getFaviconUrl())
                || !SafeUrlValidator.isHttpOrHttpsOrBlank(form.getLogoUrl())) {
            throw new IllegalArgumentException("Favicon and logo URLs must start with http:// or https://");
        }
        if (!HexColorValidator.isHexOrBlank(form.getPrimaryColor())
                || !HexColorValidator.isHexOrBlank(form.getPrimaryColorDark())
                || !HexColorValidator.isHexOrBlank(form.getAccentColor())) {
            throw new IllegalArgumentException("Colors must be hex values like #06690f");
        }

        AppBranding existing = get();
        existing.setFaviconUrl(blankToNull(form.getFaviconUrl()));
        existing.setLogoUrl(blankToNull(form.getLogoUrl()));
        existing.setPrimaryColor(blankToNull(form.getPrimaryColor()));
        existing.setPrimaryColorDark(blankToNull(form.getPrimaryColorDark()));
        existing.setAccentColor(blankToNull(form.getAccentColor()));
        return repository.save(existing);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
