package org.confcms.cms.conference;

// Thrown by ConferenceService.getActiveConference() when no conference is configured as
// active. A dedicated type (rather than a generic IllegalStateException) so
// GlobalExceptionHandler can route this one specific, real state (a fresh install with zero
// conferences configured) to its own page without risking catching an unrelated
// IllegalStateException thrown elsewhere in the app for a different reason.
public class NoActiveConferenceException extends IllegalStateException {
    public NoActiveConferenceException(String message) {
        super(message);
    }
}
