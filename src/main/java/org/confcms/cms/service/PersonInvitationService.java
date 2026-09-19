package org.confcms.cms.service;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.PersonInvitation;
import org.confcms.cms.review.domain.ReviewDecline;
import org.springframework.stereotype.Service;

@Service
public class PersonInvitationService {

    public PersonInvitation createReviewerSuggestionInvitation(ReviewDecline suggestedBy, Conference conference,
                                                                 String name, String email) {
        throw new UnsupportedOperationException("Implemented in Task 6");
    }
}
