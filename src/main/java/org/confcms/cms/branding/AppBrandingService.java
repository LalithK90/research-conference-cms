package org.confcms.cms.branding;

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

    // Blank fields are stored as null, not "" -- templates check faviconUrl != null to decide
    // whether to fall back to the built-in default, and an empty string would pass that check
    // and render an empty href/src instead of falling back.
    @Transactional
    public AppBranding save(AppBranding form) {
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
