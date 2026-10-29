package org.confcms.cms.service;

import java.net.URI;
import java.net.URISyntaxException;

// Rejects javascript:/data: and similarly schemed values an admin could otherwise save into a
// URL field (sponsor website/logo, speaker photo, committee photo) that templates render
// directly into th:href/th:src -- without this, a saved javascript: URI executes for any
// visitor who clicks the link.
public final class SafeUrlValidator {

    private SafeUrlValidator() {
    }

    public static boolean isHttpOrHttpsOrBlank(String url) {
        if (url == null || url.isBlank()) {
            return true;
        }
        try {
            String scheme = new URI(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
