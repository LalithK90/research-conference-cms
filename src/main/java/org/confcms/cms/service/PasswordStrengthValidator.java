package org.confcms.cms.service;

// Minimum password strength enforced wherever a password is ever set (signup, password
// reset) -- AuthService.registerUser previously accepted any non-blank string, including
// single-character passwords, since the only check was "is this email already taken".
public final class PasswordStrengthValidator {

    private static final int MIN_LENGTH = 8;

    private PasswordStrengthValidator() {
    }

    public static boolean isStrongEnough(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            hasLetter = hasLetter || Character.isLetter(c);
            hasDigit = hasDigit || Character.isDigit(c);
        }
        return hasLetter && hasDigit;
    }
}
