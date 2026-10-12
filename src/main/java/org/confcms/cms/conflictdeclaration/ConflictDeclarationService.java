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
    // this system would otherwise have to expose.
    //
    // Deliberately silent on whether the email actually matched anything: an earlier version
    // threw when the email didn't resolve to a registered author here, which a background
    // security review correctly flagged as an author-participation oracle -- a reviewer could
    // enumerate guessed emails and learn who's actually submitting to this blind-review
    // conference purely from which guesses "succeeded" vs "failed", exactly the fact blind
    // review exists to hide. This method instead always completes the same way for the caller
    // (ReviewUiController shows one fixed message regardless of outcome): a matching email
    // creates a real ConflictDeclaration; a non-matching one is a deliberate no-op, not
    // persisted (there's no legitimate use for storing an arbitrary, unverified email, and
    // doing so would itself be a minor data-quality/spam surface).
    @Transactional
    public void declareConflictByEmail(User reviewer, Conference conference, String email) {
        // Both lookups always run, win or lose, so a non-matching email doesn't finish
        // measurably faster than a matching one -- the same timing-side-channel discipline
        // this codebase already applies to the magic-link request endpoint.
        //
        // Both checks are anchored to the same literal `email` string (not to each other's
        // resolved record), so a DB-collation-vs-equalsIgnoreCase case mismatch can only ever
        // produce a false negative here (a real author's declaration silently not applying,
        // the already-fail-safe outcome this method defaults to) -- never a false positive or
        // an oracle, since both branches observe the exact same input either way.
        var declaredAgainstUser = userRepository.findByEmail(email);
        boolean isAuthorHere = paperRepository.findByConferenceId(conference.getId()).stream()
                .flatMap(p -> p.getAuthors().stream())
                .anyMatch(a -> a.getEmail().equalsIgnoreCase(email));

        if (declaredAgainstUser.isPresent() && isAuthorHere) {
            declareConflict(reviewer, conference, declaredAgainstUser.get());
        }
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

    // Enforces server-side what ReviewUiController's conflicts() view only enforces by not
    // SHOWING the browsable author list: a background security review correctly pointed out
    // that hiding the list in the UI does nothing to stop a direct POST to this declare-by-id
    // path with a guessed user ID, for a blind-review conference, where success vs. "not an
    // author here" is just as much an author-participation oracle as the email path was.
    // Also closes a gap the id-based path never had a check for at all: nothing previously
    // confirmed declaredAgainstUser is actually an author at this conference, so a conflict
    // could be declared against any registered user by id regardless of participation.
    @Transactional
    public void declareConflictById(User reviewer, Conference conference, Long declaredAgainstUserId) {
        if (conference.isBlindReview()) {
            return;
        }

        // Both lookups always run, win or lose -- see declareConflictByEmail for why
        // (closing the matching timing side of the same oracle class).
        var declaredAgainstUser = userRepository.findById(declaredAgainstUserId);
        boolean isAuthorHere = declaredAgainstUser.isPresent()
                && paperRepository.findByConferenceId(conference.getId()).stream()
                        .flatMap(p -> p.getAuthors().stream())
                        .anyMatch(a -> a.getEmail().equalsIgnoreCase(declaredAgainstUser.get().getEmail()));

        if (isAuthorHere) {
            declareConflict(reviewer, conference, declaredAgainstUser.get());
        }
    }
}
