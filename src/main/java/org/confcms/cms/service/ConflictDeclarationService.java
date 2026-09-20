package org.confcms.cms.service;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.ConflictDeclaration;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.ConflictDeclarationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConflictDeclarationService {

    private final ConflictDeclarationRepository repository;

    public boolean hasConflict(User reviewer, Conference conference, User declaredAgainstUser) {
        return repository.existsByConferenceIdAndReviewerIdAndDeclaredAgainstUserId(
                conference.getId(), reviewer.getId(), declaredAgainstUser.getId());
    }

    @Transactional
    public ConflictDeclaration declareConflict(User reviewer, Conference conference, User declaredAgainstUser) {
        if (hasConflict(reviewer, conference, declaredAgainstUser)) {
            return repository.findByConferenceIdAndReviewerIdAndDeclaredAgainstUserId(
                    conference.getId(), reviewer.getId(), declaredAgainstUser.getId()).orElseThrow();
        }

        ConflictDeclaration declaration = new ConflictDeclaration();
        declaration.setConference(conference);
        declaration.setReviewer(reviewer);
        declaration.setDeclaredAgainstUser(declaredAgainstUser);
        return repository.save(declaration);
    }
}
