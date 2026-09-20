package org.confcms.cms.service;

import org.confcms.cms.auth.service.AuthService;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.*;
import org.confcms.cms.repository.PersonInvitationRepository;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.review.domain.ReviewDecline;
import org.confcms.cms.review.service.ReviewAssignmentService;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperAuthor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PersonInvitationService {

    private static final int MAX_RESEND_COUNT = 5;
    private static final long MIN_RESEND_INTERVAL_MINUTES = 60;
    private static final long INVITATION_VALIDITY_HOURS = 72;

    private final PersonInvitationRepository repository;
    private final CommitteeService committeeService;
    private final EmailService emailService;
    private final AuthService authService;
    private final UserRepository userRepository;
    private final ReviewAssignmentService reviewAssignmentService;

    // Explicit constructor (not @RequiredArgsConstructor) so @Lazy reliably lands on
    // the reviewAssignmentService parameter: it breaks the ReviewAssignmentService <->
    // PersonInvitationService circular dependency (declineAssignment needs to create an
    // invitation; acceptInvitation needs to create the resulting assignment). Only this
    // side is lazy since acceptInvitation calls it just once per REVIEWER_SUGGESTION
    // accept, versus ReviewAssignmentService.declineAssignment being the more central path.
    public PersonInvitationService(PersonInvitationRepository repository,
                                    CommitteeService committeeService,
                                    EmailService emailService,
                                    AuthService authService,
                                    UserRepository userRepository,
                                    @Lazy ReviewAssignmentService reviewAssignmentService) {
        this.repository = repository;
        this.committeeService = committeeService;
        this.emailService = emailService;
        this.authService = authService;
        this.userRepository = userRepository;
        this.reviewAssignmentService = reviewAssignmentService;
    }

    private void requireChairOrAdmin(User actingUser, Conference conference) {
        boolean isAdmin = actingUser.getRole() == Role.ADMIN;
        if (!isAdmin && !committeeService.isChairOrCoChair(actingUser, conference)) {
            throw new SecurityException("Not authorized to manage invitations for this conference");
        }
    }

    private void sendInvitationEmail(PersonInvitation invitation) {
        String url = "http://localhost:8080/invitations/accept?token=" + invitation.getToken();
        String subject = "You've been invited to join " + invitation.getConference().getTitle();
        String body = "Hello " + invitation.getName() + ",\n\n"
                + "You've been invited to join as a " + invitation.getPurpose().name().toLowerCase().replace('_', ' ') + ".\n"
                + "Click to accept: " + url + "\n"
                + "This link expires at: " + invitation.getExpiresAt();
        emailService.sendSimpleEmail(invitation.getEmail(), subject, body);
    }

    @Transactional
    public PersonInvitation createReviewerSuggestionInvitation(ReviewDecline suggestedBy, Conference conference,
                                                                 String name, String email) {
        PersonInvitation invitation = new PersonInvitation();
        invitation.setName(name);
        invitation.setEmail(email);
        invitation.setPurpose(InvitationPurpose.REVIEWER_SUGGESTION);
        invitation.setStatus(InvitationStatus.PENDING_APPROVAL);
        invitation.setConference(conference);
        invitation.setSuggestedBy(suggestedBy);
        invitation.setToken(UUID.randomUUID().toString());
        invitation.setExpiresAt(LocalDateTime.now().plusHours(INVITATION_VALIDITY_HOURS));
        return repository.save(invitation);
    }

    @Transactional
    public PersonInvitation approve(User actingUser, Long invitationId) {
        PersonInvitation invitation = repository.findById(invitationId)
                .orElseThrow(() -> new IllegalArgumentException("Invitation not found"));
        if (invitation.getPurpose() != InvitationPurpose.REVIEWER_SUGGESTION) {
            throw new IllegalStateException("Only reviewer-suggestion invitations require approval");
        }
        if (invitation.getStatus() != InvitationStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Only pending invitations can be approved");
        }
        requireChairOrAdmin(actingUser, invitation.getConference());

        invitation.setStatus(InvitationStatus.INVITED);
        invitation.setInvitedBy(actingUser);
        invitation.setToken(UUID.randomUUID().toString());
        invitation.setExpiresAt(LocalDateTime.now().plusHours(INVITATION_VALIDITY_HOURS));
        invitation.setLastSentAt(LocalDateTime.now());
        PersonInvitation saved = repository.save(invitation);
        sendInvitationEmail(saved);
        return saved;
    }

    @Transactional
    public PersonInvitation reject(User actingUser, Long invitationId, String reason) {
        PersonInvitation invitation = repository.findById(invitationId)
                .orElseThrow(() -> new IllegalArgumentException("Invitation not found"));
        if (invitation.getPurpose() != InvitationPurpose.REVIEWER_SUGGESTION) {
            throw new IllegalStateException("Only reviewer-suggestion invitations can be rejected");
        }
        if (invitation.getStatus() != InvitationStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("Only pending invitations can be rejected");
        }
        requireChairOrAdmin(actingUser, invitation.getConference());

        invitation.setStatus(InvitationStatus.REJECTED);
        return repository.save(invitation);
    }

    @Transactional
    public PersonInvitation resend(User actingUser, Long invitationId) {
        PersonInvitation invitation = repository.findById(invitationId)
                .orElseThrow(() -> new IllegalArgumentException("Invitation not found"));

        if (invitation.getStatus() != InvitationStatus.INVITED) {
            throw new IllegalStateException("Only invitations that have been sent can be resent");
        }

        boolean authorized = switch (invitation.getPurpose()) {
            case REVIEWER_SUGGESTION -> actingUser.getRole() == Role.ADMIN
                    || committeeService.isChairOrCoChair(actingUser, invitation.getConference())
                    || (invitation.getSuggestedBy() != null
                        && invitation.getSuggestedBy().getAssignment().getReviewer().getId().equals(actingUser.getId()));
            case CO_AUTHOR -> actingUser.getRole() == Role.ADMIN
                    || (invitation.getPaper() != null
                        && invitation.getPaper().getSubmitter().getId().equals(actingUser.getId()));
            case REVIEWER_RECRUITMENT -> actingUser.getRole() == Role.ADMIN
                    || (invitation.getInvitedBy() != null && invitation.getInvitedBy().getId().equals(actingUser.getId()));
        };
        if (!authorized) {
            throw new SecurityException("Not authorized to resend this invitation");
        }

        if (invitation.getResendCount() >= MAX_RESEND_COUNT) {
            throw new IllegalStateException("Maximum resend attempts reached for this invitation");
        }
        if (invitation.getLastSentAt() != null
                && invitation.getLastSentAt().isAfter(LocalDateTime.now().minusMinutes(MIN_RESEND_INTERVAL_MINUTES))) {
            throw new IllegalStateException("Please wait before resending this invitation");
        }

        invitation.setToken(UUID.randomUUID().toString());
        invitation.setExpiresAt(LocalDateTime.now().plusHours(INVITATION_VALIDITY_HOURS));
        invitation.setResendCount(invitation.getResendCount() + 1);
        invitation.setLastSentAt(LocalDateTime.now());
        PersonInvitation saved = repository.save(invitation);
        sendInvitationEmail(saved);
        return saved;
    }

    @Transactional
    public User acceptInvitation(String token, String password) {
        PersonInvitation invitation = repository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Invalid invitation link"));

        if (invitation.isUsed()) {
            throw new IllegalArgumentException("This invitation has already been used");
        }
        if (invitation.getStatus() != InvitationStatus.INVITED) {
            throw new IllegalArgumentException("This invitation is not currently valid to accept");
        }
        if (invitation.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("This invitation has expired");
        }

        Role role = invitation.getPurpose() == InvitationPurpose.CO_AUTHOR ? Role.AUTHOR : Role.REVIEWER;
        User user = authService.registerUser(invitation.getEmail(), password, invitation.getName(), role);

        invitation.setUsed(true);
        invitation.setStatus(InvitationStatus.ACCEPTED);
        repository.save(invitation);

        if (invitation.getPurpose() == InvitationPurpose.REVIEWER_SUGGESTION && invitation.getSuggestedBy() != null) {
            Paper originalPaper = invitation.getSuggestedBy().getAssignment().getPaper();
            reviewAssignmentService.assignReviewerFromInvitation(originalPaper, user);
        }

        return user;
    }

    @Transactional
    public PersonInvitation inviteCoAuthor(Paper paper, PaperAuthor author) {
        PersonInvitation invitation = new PersonInvitation();
        invitation.setName(author.getFullName());
        invitation.setEmail(author.getEmail());
        invitation.setPurpose(InvitationPurpose.CO_AUTHOR);
        invitation.setStatus(InvitationStatus.INVITED);
        invitation.setConference(paper.getConference());
        invitation.setPaper(paper);
        invitation.setToken(UUID.randomUUID().toString());
        invitation.setExpiresAt(LocalDateTime.now().plusHours(INVITATION_VALIDITY_HOURS));
        invitation.setLastSentAt(LocalDateTime.now());
        PersonInvitation saved = repository.save(invitation);
        sendInvitationEmail(saved);
        return saved;
    }

    @Transactional
    public PersonInvitation recruitReviewer(User actingUser, Conference conference, String name, String email) {
        boolean isAdmin = actingUser.getRole() == Role.ADMIN;
        if (!isAdmin && !committeeService.hasAnyCommitteeRole(actingUser, conference)) {
            throw new SecurityException("Only conference committee members can recruit reviewers");
        }

        PersonInvitation invitation = new PersonInvitation();
        invitation.setName(name);
        invitation.setEmail(email);
        invitation.setPurpose(InvitationPurpose.REVIEWER_RECRUITMENT);
        invitation.setStatus(InvitationStatus.INVITED);
        invitation.setConference(conference);
        invitation.setInvitedBy(actingUser);
        invitation.setToken(UUID.randomUUID().toString());
        invitation.setExpiresAt(LocalDateTime.now().plusHours(INVITATION_VALIDITY_HOURS));
        invitation.setLastSentAt(LocalDateTime.now());
        PersonInvitation saved = repository.save(invitation);
        sendInvitationEmail(saved);
        return saved;
    }
}
