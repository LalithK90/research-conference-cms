package org.confcms.cms.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

// Routes a cold ORCID login that needs one more step (no email from ORCID to create an
// account from) to its own completion page, instead of the generic /login?error every other
// OAuth2AuthenticationException still falls through to.
@Component
public class OAuth2LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    public OAuth2LoginFailureHandler() {
        super("/login?error");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                         AuthenticationException exception) throws IOException, ServletException {
        if (exception instanceof OrcidSignupRequiresEmailException orcidException) {
            // Pending-signup data lives only in this one-time session attribute: just enough to
            // finish creating the account (ORCID iD + the name ORCID returned), read and cleared
            // by AuthRestController's completeOrcidSignup on the next request.
            request.getSession().setAttribute("pendingOrcidSignupId", orcidException.getOrcidId());
            request.getSession().setAttribute("pendingOrcidSignupName", orcidException.getName());
            getRedirectStrategy().sendRedirect(request, response, "/auth/complete-orcid-signup");
            return;
        }
        super.onAuthenticationFailure(request, response, exception);
    }
}
