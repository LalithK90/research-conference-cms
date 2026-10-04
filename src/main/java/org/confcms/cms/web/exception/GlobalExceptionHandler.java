package org.confcms.cms.web.exception;

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

    @ExceptionHandler(Exception.class)
    public String handleException(Exception e, Model model) {
        model.addAttribute("error", e.getMessage());
        return "error";
    }
}
