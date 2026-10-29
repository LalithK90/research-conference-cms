package org.confcms.cms.service;

import java.util.regex.Pattern;

// Rejects anything but a strict #rgb/#rrggbb hex value for a color field that a template
// renders directly into a <style> block (AppBranding's primaryColor/primaryColorDark/
// accentColor) -- without this, an admin could inject CSS that breaks out of the custom
// property declaration (e.g. "red; } body { display: none !important; } .x {").
public final class HexColorValidator {

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{3}([0-9a-fA-F]{3})?$");

    private HexColorValidator() {
    }

    public static boolean isHexOrBlank(String color) {
        if (color == null || color.isBlank()) {
            return true;
        }
        return HEX_COLOR.matcher(color.trim()).matches();
    }
}
