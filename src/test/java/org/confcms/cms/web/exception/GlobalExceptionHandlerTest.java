package org.confcms.cms.web.exception;

import org.confcms.cms.conference.NoActiveConferenceException;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleNoActiveConferenceReturnsDedicatedPage() {
        String view = handler.handleNoActiveConference();

        assertThat(view).isEqualTo("public/no_active_conference");
    }

    @Test
    void handleExceptionFallsBackToGenericErrorPageForOtherExceptions() {
        Model model = new ExtendedModelMap();

        String view = handler.handleException(new IllegalStateException("some other failure"), model);

        assertThat(view).isEqualTo("error");
        assertThat(model.getAttribute("error")).isEqualTo("some other failure");
    }

    @Test
    void noActiveConferenceExceptionIsAnIllegalStateExceptionSubtype() {
        // So any pre-existing catch (IllegalStateException e) elsewhere keeps working
        // unchanged -- this is a narrowing, not a new unrelated exception hierarchy.
        assertThat(new NoActiveConferenceException("msg")).isInstanceOf(IllegalStateException.class);
    }
}
