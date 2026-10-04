package org.confcms.cms.web.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {

    // ConferenceService.getActiveConference() throws this for every public page that calls it
    // directly (not just the shared nav's own @ModelAttribute, which already swallows it) when
    // no conference is configured as active yet -- a real state for a fresh install. A
    // dedicated page here covers every current and future public route that hits this same
    // exception, rather than adding a try/catch to each controller method individually.
    @ExceptionHandler(org.confcms.cms.conference.NoActiveConferenceException.class)
    public String handleNoActiveConference() {
        return "public/no_active_conference";
    }

    // An AccessDeniedException thrown from inside a controller method (as opposed to a
    // @PreAuthorize denial, which Spring Security's ExceptionTranslationFilter already handles
    // before this @ControllerAdvice is ever reached) would otherwise fall into the generic
    // handleException() below, returning HTTP 200 with a Whitelabel error body -- a real
    // authorization denial disguised as a successful response.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<String> handleAccessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public String handleException(Exception e, Model model) {
        model.addAttribute("error", e.getMessage());
        return "error";
    }
}
