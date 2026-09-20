package org.confcms.cms.service;

import org.confcms.cms.domain.ConflictDeclaration;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.ConflictDeclarationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConflictDeclarationServiceTest {

    @Mock
    private ConflictDeclarationRepository repository;

    private ConflictDeclarationService service;
    private Conference conference;
    private User reviewer;
    private User author;

    @BeforeEach
    void setUp() {
        service = new ConflictDeclarationService(repository);
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
}
