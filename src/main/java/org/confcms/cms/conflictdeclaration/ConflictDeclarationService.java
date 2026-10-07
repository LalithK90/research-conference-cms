package org.confcms.cms.conflictdeclaration;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.paper.Paper;
import org.confcms.cms.paper.PaperRepository;
import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ConflictDeclarationService {

    private final ConflictDeclarationRepository repository;
    private final PaperRepository paperRepository;
    private final UserRepository userRepository;

    public boolean hasConflict(User reviewer, Conference conference, User declaredAgainstUser) {
        return repository.existsByConferenceIdAndReviewerIdAndDeclaredAgainstUserId(
                conference.getId(), reviewer.getId(), declaredAgainstUser.getId());
    }

    // Every distinct author on this conference's papers who is also a registered User account
    // (the same resolution autoAssignReviewers already does for its own conflict-exclusion
    // check -- a conflict can only be declared against a real account, since
    // ConflictDeclaration.declaredAgainstUser is a User, not a free-text PaperAuthor row).
    // LinkedHashMap keyed by user id de-duplicates a co-author appearing on multiple papers.
    @Transactional(readOnly = true)
    public List<User> declarableAuthors(Conference conference) {
        List<Paper> papers = paperRepository.findByConferenceId(conference.getId());
        Map<Long, User> byId = new LinkedHashMap<>();
        for (Paper paper : papers) {
            for (var author : paper.getAuthors()) {
                userRepository.findByEmail(author.getEmail())
                        .ifPresent(user -> byId.putIfAbsent(user.getId(), user));
            }
        }
        return List.copyOf(byId.values());
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
