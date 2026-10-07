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
    //
    // ONLY safe to show to a reviewer for a NON-blind-review conference: this returns every
    // author's real name, which a blind-review conference's reviewers are never supposed to
    // see (the same anonymity ReviewService.toReviewView already enforces by emptying
    // authorNames for a blind-review paper). Callers must gate this on
    // conference.isBlindReview() themselves -- it is not checked here, so misuse elsewhere in
    // this codebase stays possible; the only current caller (ReviewUiController) does gate it.
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

    // For a blind-review conference: the reviewer already knows (outside this system) who
    // they have a conflict with, and types that person's email rather than browsing a roster
    // this system would otherwise have to expose. Only declares a conflict if that email
    // belongs to a registered User who is actually an author at this conference -- an
    // arbitrary email (someone uninvolved, a typo, or a probe for "is this email registered
    // and is it an author here") is rejected rather than silently accepted, so this can't be
    // used to test account existence or conference participation.
    @Transactional
    public ConflictDeclaration declareConflictByEmail(User reviewer, Conference conference, String email) {
        User declaredAgainstUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No author at this conference is registered with that email address."));

        boolean isAuthorHere = paperRepository.findByConferenceId(conference.getId()).stream()
                .flatMap(p -> p.getAuthors().stream())
                .anyMatch(a -> a.getEmail().equalsIgnoreCase(email));
        if (!isAuthorHere) {
            throw new IllegalArgumentException(
                    "No author at this conference is registered with that email address.");
        }

        return declareConflict(reviewer, conference, declaredAgainstUser);
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
