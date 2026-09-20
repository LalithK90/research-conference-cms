package org.confcms.cms.service;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.*;
import org.confcms.cms.repository.PersonInvitationRepository;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.domain.ReviewDecline;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.auth.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PersonInvitationServiceTest {

    @Mock
    private PersonInvitationRepository repository;
    @Mock
    private CommitteeService committeeService;
    @Mock
    private EmailService emailService;
    @Mock
    private AuthService authService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private org.confcms.cms.review.service.ReviewAssignmentService reviewAssignmentService;

    private PersonInvitationService service;
    private Conference conference;
    private User chairUser;
    private User strangerUser;

    @BeforeEach
    void setUp() {
        service = new PersonInvitationService(repository, committeeService, emailService, authService, userRepository, reviewAssignmentService);

        conference = new Conference();
        conference.setId(1L);

        chairUser = new User();
        chairUser.setId(20L);
        chairUser.setRole(Role.REVIEWER);

        strangerUser = new User();
        strangerUser.setId(30L);
        strangerUser.setRole(Role.REVIEWER);
    }

    private PersonInvitation pendingInvitation() {
        PersonInvitation invitation = new PersonInvitation();
        invitation.setId(100L);
        invitation.setConference(conference);
        invitation.setPurpose(InvitationPurpose.REVIEWER_SUGGESTION);
        invitation.setStatus(InvitationStatus.PENDING_APPROVAL);
        invitation.setName("Jane Doe");
        invitation.setEmail("jane@example.com");
        return invitation;
    }

    @Test
    void approveRejectsNonChairNonAdmin() {
        PersonInvitation invitation = pendingInvitation();
        when(repository.findById(100L)).thenReturn(Optional.of(invitation));
        when(committeeService.isChairOrCoChair(strangerUser, conference)).thenReturn(false);

        assertThatThrownBy(() -> service.approve(strangerUser, 100L))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void approveTransitionsToInvitedAndSendsEmail() {
        PersonInvitation invitation = pendingInvitation();
        when(repository.findById(100L)).thenReturn(Optional.of(invitation));
        when(committeeService.isChairOrCoChair(chairUser, conference)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.approve(chairUser, 100L);

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.INVITED);
        assertThat(invitation.getInvitedBy()).isEqualTo(chairUser);
        assertThat(invitation.getToken()).isNotBlank();
        verify(emailService).sendSimpleEmail(eq("jane@example.com"), any(), any());
    }

    @Test
    void rejectTransitionsToRejectedWithoutEmail() {
        PersonInvitation invitation = pendingInvitation();
        when(repository.findById(100L)).thenReturn(Optional.of(invitation));
        when(committeeService.isChairOrCoChair(chairUser, conference)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reject(chairUser, 100L, "not qualified");

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.REJECTED);
        verifyNoInteractions(emailService);
    }

    @Test
    void resendRateLimitedByCount() {
        PersonInvitation invitation = pendingInvitation();
        invitation.setStatus(InvitationStatus.INVITED);
        invitation.setResendCount(5);
        invitation.setLastSentAt(LocalDateTime.now().minusDays(1));
        when(repository.findById(100L)).thenReturn(Optional.of(invitation));
        when(committeeService.isChairOrCoChair(chairUser, conference)).thenReturn(true);

        assertThatThrownBy(() -> service.resend(chairUser, 100L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resendRateLimitedByInterval() {
        PersonInvitation invitation = pendingInvitation();
        invitation.setStatus(InvitationStatus.INVITED);
        invitation.setResendCount(1);
        invitation.setLastSentAt(LocalDateTime.now().minusMinutes(10));
        when(repository.findById(100L)).thenReturn(Optional.of(invitation));
        when(committeeService.isChairOrCoChair(chairUser, conference)).thenReturn(true);

        assertThatThrownBy(() -> service.resend(chairUser, 100L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resendAllowedAfterIntervalAndIncrementsCount() {
        PersonInvitation invitation = pendingInvitation();
        invitation.setStatus(InvitationStatus.INVITED);
        invitation.setResendCount(1);
        invitation.setLastSentAt(LocalDateTime.now().minusHours(2));
        when(repository.findById(100L)).thenReturn(Optional.of(invitation));
        when(committeeService.isChairOrCoChair(chairUser, conference)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resend(chairUser, 100L);

        assertThat(invitation.getResendCount()).isEqualTo(2);
        verify(emailService).sendSimpleEmail(eq("jane@example.com"), any(), any());
    }

    @Test
    void approveRejectsInvitationNotInPendingApproval() {
        PersonInvitation invitation = pendingInvitation();
        invitation.setStatus(InvitationStatus.REJECTED);
        when(repository.findById(100L)).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.approve(chairUser, 100L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectRejectsInvitationNotInPendingApproval() {
        PersonInvitation invitation = pendingInvitation();
        invitation.setStatus(InvitationStatus.ACCEPTED);
        when(repository.findById(100L)).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.reject(chairUser, 100L, "reason"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resendRejectsInvitationStillPendingApproval() {
        PersonInvitation invitation = pendingInvitation();
        // status is PENDING_APPROVAL from pendingInvitation() -- never approved, so not resendable
        when(repository.findById(100L)).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.resend(chairUser, 100L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptInvitationRejectsPendingApprovalInvitation() {
        PersonInvitation invitation = pendingInvitation();
        invitation.setToken("tok-789");
        invitation.setExpiresAt(LocalDateTime.now().plusHours(1));
        // status is PENDING_APPROVAL -- was never approved by a chair, so must not be acceptable
        when(repository.findByToken("tok-789")).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.acceptInvitation("tok-789", "password123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptInvitationRejectsAlreadyAcceptedInvitation() {
        PersonInvitation invitation = pendingInvitation();
        invitation.setStatus(InvitationStatus.ACCEPTED);
        invitation.setToken("tok-999");
        invitation.setExpiresAt(LocalDateTime.now().plusHours(1));
        when(repository.findByToken("tok-999")).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.acceptInvitation("tok-999", "password123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptInvitationRejectsExpiredToken() {
        PersonInvitation invitation = pendingInvitation();
        invitation.setStatus(InvitationStatus.INVITED);
        invitation.setToken("tok-123");
        invitation.setExpiresAt(LocalDateTime.now().minusHours(1));
        when(repository.findByToken("tok-123")).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.acceptInvitation("tok-123", "password123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptInvitationRejectsUsedToken() {
        PersonInvitation invitation = pendingInvitation();
        invitation.setStatus(InvitationStatus.INVITED);
        invitation.setToken("tok-123");
        invitation.setExpiresAt(LocalDateTime.now().plusHours(1));
        invitation.setUsed(true);
        when(repository.findByToken("tok-123")).thenReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.acceptInvitation("tok-123", "password123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptInvitationCreatesUserWithReviewerRoleForReviewerSuggestion() {
        PersonInvitation invitation = pendingInvitation();
        invitation.setToken("tok-123");
        invitation.setStatus(InvitationStatus.INVITED);
        invitation.setExpiresAt(LocalDateTime.now().plusHours(1));

        ReviewDecline decline = new ReviewDecline();
        ReviewAssignment originalAssignment = new ReviewAssignment();
        Paper paper = new Paper();
        originalAssignment.setPaper(paper);
        decline.setAssignment(originalAssignment);
        invitation.setSuggestedBy(decline);

        when(repository.findByToken("tok-123")).thenReturn(Optional.of(invitation));
        User createdUser = new User();
        createdUser.setId(200L);
        when(authService.registerUser("jane@example.com", "password123", "Jane Doe", Role.REVIEWER))
                .thenReturn(createdUser);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.acceptInvitation("tok-123", "password123");

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(invitation.isUsed()).isTrue();
        verify(reviewAssignmentService).assignReviewerFromInvitation(paper, createdUser);
    }

    @Test
    void acceptInvitationCreatesUserWithAuthorRoleForCoAuthor() {
        PersonInvitation invitation = pendingInvitation();
        invitation.setPurpose(InvitationPurpose.CO_AUTHOR);
        invitation.setToken("tok-456");
        invitation.setStatus(InvitationStatus.INVITED);
        invitation.setExpiresAt(LocalDateTime.now().plusHours(1));

        when(repository.findByToken("tok-456")).thenReturn(Optional.of(invitation));
        User createdUser = new User();
        when(authService.registerUser("jane@example.com", "password123", "Jane Doe", Role.AUTHOR))
                .thenReturn(createdUser);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.acceptInvitation("tok-456", "password123");

        assertThat(invitation.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        verifyNoInteractions(reviewAssignmentService);
    }

    @Test
    void inviteCoAuthorCreatesInvitedStatusDirectly() {
        Paper paper = new Paper();
        paper.setConference(conference);
        org.confcms.cms.submission.domain.PaperAuthor author = new org.confcms.cms.submission.domain.PaperAuthor();
        author.setFullName("Co Author");
        author.setEmail("coauthor@example.com");

        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PersonInvitation result = service.inviteCoAuthor(paper, author);

        assertThat(result.getStatus()).isEqualTo(InvitationStatus.INVITED);
        assertThat(result.getPurpose()).isEqualTo(InvitationPurpose.CO_AUTHOR);
        verify(emailService).sendSimpleEmail(eq("coauthor@example.com"), any(), any());
    }

    @Test
    void recruitReviewerRejectsUserWithNoCommitteeRole() {
        when(committeeService.hasAnyCommitteeRole(strangerUser, conference)).thenReturn(false);

        assertThatThrownBy(() -> service.recruitReviewer(strangerUser, conference, "New Person", "new@example.com"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void recruitReviewerCreatesInvitedStatusDirectly() {
        when(committeeService.hasAnyCommitteeRole(chairUser, conference)).thenReturn(true);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PersonInvitation result = service.recruitReviewer(chairUser, conference, "New Person", "new@example.com");

        assertThat(result.getStatus()).isEqualTo(InvitationStatus.INVITED);
        assertThat(result.getPurpose()).isEqualTo(InvitationPurpose.REVIEWER_RECRUITMENT);
        assertThat(result.getInvitedBy()).isEqualTo(chairUser);
        verify(emailService).sendSimpleEmail(eq("new@example.com"), any(), any());
    }
}
