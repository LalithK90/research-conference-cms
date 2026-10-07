package org.confcms.cms.conflictdeclaration;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.paper.Paper;
import org.confcms.cms.paper.PaperAuthor;
import org.confcms.cms.paper.PaperRepository;
import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConflictDeclarationServiceTest {

    @Mock
    private ConflictDeclarationRepository repository;
    @Mock
    private PaperRepository paperRepository;
    @Mock
    private UserRepository userRepository;

    private ConflictDeclarationService service;
    private Conference conference;
    private User reviewer;
    private User author;

    @BeforeEach
    void setUp() {
        service = new ConflictDeclarationService(repository, paperRepository, userRepository);
        conference = new Conference();
        conference.setId(1L);
        reviewer = new User();
        reviewer.setId(10L);
        author = new User();
        author.setId(20L);
    }

    @Test
    void declareConflictCreatesNewRow() {
        when(repository.existsByConferenceIdAndReviewerIdAndDeclaredAgainstUserId(1L, 10L, 20L)).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ConflictDeclaration result = service.declareConflict(reviewer, conference, author);

        assertThat(result.getReviewer()).isEqualTo(reviewer);
        assertThat(result.getDeclaredAgainstUser()).isEqualTo(author);
        verify(repository).save(any());
    }

    @Test
    void declareConflictIsIdempotent() {
        when(repository.existsByConferenceIdAndReviewerIdAndDeclaredAgainstUserId(1L, 10L, 20L)).thenReturn(true);
        ConflictDeclaration existing = new ConflictDeclaration();
        existing.setReviewer(reviewer);
        existing.setDeclaredAgainstUser(author);
        when(repository.findByConferenceIdAndReviewerIdAndDeclaredAgainstUserId(1L, 10L, 20L)).thenReturn(java.util.Optional.of(existing));

        service.declareConflict(reviewer, conference, author);

        verify(repository, never()).save(any());
    }

    @Test
    void hasConflictTrueWhenDeclared() {
        when(repository.existsByConferenceIdAndReviewerIdAndDeclaredAgainstUserId(1L, 10L, 20L)).thenReturn(true);

        assertThat(service.hasConflict(reviewer, conference, author)).isTrue();
    }

    @Test
    void declarableAuthorsReturnsDistinctRegisteredAuthorsAcrossPapers() {
        PaperAuthor authorOnPaper1 = new PaperAuthor();
        authorOnPaper1.setFullName("Jane Doe");
        authorOnPaper1.setEmail("jane@example.com");

        PaperAuthor sameAuthorOnPaper2 = new PaperAuthor();
        sameAuthorOnPaper2.setFullName("Jane Doe");
        sameAuthorOnPaper2.setEmail("jane@example.com");

        Paper paper1 = new Paper();
        paper1.getAuthors().add(authorOnPaper1);
        Paper paper2 = new Paper();
        paper2.getAuthors().add(sameAuthorOnPaper2);

        when(paperRepository.findByConferenceId(1L)).thenReturn(List.of(paper1, paper2));
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(author));

        List<User> result = service.declarableAuthors(conference);

        assertThat(result).containsExactly(author);
    }

    @Test
    void declarableAuthorsSkipsAuthorsWithNoRegisteredAccount() {
        PaperAuthor unregisteredAuthor = new PaperAuthor();
        unregisteredAuthor.setFullName("No Account");
        unregisteredAuthor.setEmail("noaccount@example.com");

        Paper paper = new Paper();
        paper.getAuthors().add(unregisteredAuthor);

        when(paperRepository.findByConferenceId(1L)).thenReturn(List.of(paper));
        when(userRepository.findByEmail("noaccount@example.com")).thenReturn(Optional.empty());

        List<User> result = service.declarableAuthors(conference);

        assertThat(result).isEmpty();
    }

    @Test
    void declareConflictByEmailCreatesRealDeclarationWhenEmailIsARegisteredAuthorAtThisConference() {
        PaperAuthor paperAuthor = new PaperAuthor();
        paperAuthor.setEmail("jane@example.com");
        Paper paper = new Paper();
        paper.getAuthors().add(paperAuthor);

        when(paperRepository.findByConferenceId(1L)).thenReturn(List.of(paper));
        when(userRepository.findByEmail("jane@example.com")).thenReturn(Optional.of(author));
        when(repository.existsByConferenceIdAndReviewerIdAndDeclaredAgainstUserId(1L, 10L, 20L)).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.declareConflictByEmail(reviewer, conference, "jane@example.com");

        org.mockito.ArgumentCaptor<ConflictDeclaration> captor = org.mockito.ArgumentCaptor.forClass(ConflictDeclaration.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getDeclaredAgainstUser()).isEqualTo(author);
    }

    // The three "doesn't actually match" cases below must all behave identically from the
    // caller's point of view (no exception, nothing saved) -- a background security review
    // correctly flagged an earlier version that threw for these, since the distinct
    // success/failure outcome let a reviewer enumerate guessed emails and learn who's
    // actually submitting to a blind-review conference. There is nothing for the caller to
    // observe here that would let it tell these apart from the real-match case above except
    // the absence of a saved row, which ReviewUiController never surfaces either.

    @Test
    void declareConflictByEmailIsANoOpWhenEmailIsNotARegisteredUser() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(paperRepository.findByConferenceId(1L)).thenReturn(List.of());

        service.declareConflictByEmail(reviewer, conference, "nobody@example.com");

        verify(repository, never()).save(any());
    }

    @Test
    void declareConflictByEmailIsANoOpWhenRegisteredUserIsNotAnAuthorHere() {
        Paper paper = new Paper();
        when(paperRepository.findByConferenceId(1L)).thenReturn(List.of(paper));
        when(userRepository.findByEmail("other@example.com")).thenReturn(Optional.of(author));

        service.declareConflictByEmail(reviewer, conference, "other@example.com");

        verify(repository, never()).save(any());
    }

    @Test
    void declareConflictByEmailAlwaysQueriesBothRepositoriesRegardlessOfOutcome() {
        // Closes the timing side of the same oracle: an unregistered email must not finish
        // measurably faster than a matching one by skipping the paper/author scan.
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(paperRepository.findByConferenceId(1L)).thenReturn(List.of());

        service.declareConflictByEmail(reviewer, conference, "nobody@example.com");

        verify(userRepository).findByEmail("nobody@example.com");
        verify(paperRepository).findByConferenceId(1L);
    }
}
