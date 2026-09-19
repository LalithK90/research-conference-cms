# Reviewer Decline, Person Invitations & Conflict Declaration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a reviewer decline an assignment with a required reason and a required suggested replacement (existing user or external name+email); gate external suggestions behind Chair/Co-Chair/Admin approval before an invite email goes out; generalize the same invitation mechanism to also cover automatic co-author invites at submission time and reviewer-to-reviewer recruitment; add pre-review conflict-of-interest declaration by author name/affiliation; and fix a leftover duplicate `UserRepository` bean in `AuthService`.

**Architecture:** Three new entities (`ReviewDecline`, `PersonInvitation`, `ConflictDeclaration`) plus one new service (`PersonInvitationService`) and one method added to the existing `ReviewAssignmentService`. All authorization reuses the existing `CommitteeService` from the committee-roles feature — no new authorization primitive. The invitation-acceptance flow gets its own token/expiry fields on `PersonInvitation` rather than reusing `MagicLink` (whose `user` field is `NOT NULL` by design, since it's a login mechanism for existing accounts — an invitee doesn't have one yet).

**Tech Stack:** Spring Boot 3.5, Java 21, JPA/Hibernate, Spring Security, Thymeleaf, JUnit 5 + AssertJ + Mockito (established pattern from the last two implementation plans), Bean Validation.

---

## Context for the engineer picking this up

This plan implements `docs/superpowers/specs/2026-09-19-reviewer-decline-and-invitations-design.md`. Read that spec first for the full rationale, including why reviewer specialist-field tagging is explicitly out of scope here (queued as its own future brainstorm).

This plan builds directly on two prior merged features on `main`:
- The **domain-model-merge** work, which established `Paper.conference`, the canonical `org.confcms.cms.domain`/`.service`/`.repository` package layout, and fixed several duplicate-class bugs.
- The **committee-roles** feature, which added `CommitteeService`, `ConferenceCommitteeRole`, `CommitteeRole`, and the `isChairOrCoChair`/`hasRole` authorization pattern every task below reuses.

**One gap found while planning, corrected here:** the design spec describes `recruitReviewer`'s authorization as "reuse `CommitteeService.hasRole` checked against each [role]" — checked against the actual `CommitteeService` and found this needs a new method, since no existing method answers "does this user hold *any* committee role on this conference, regardless of which one." Task 5 adds `CommitteeService.hasAnyCommitteeRole(user, conference)` rather than three separate `hasRole` calls at the call site.

**Also confirmed:** no invitation-acceptance HTML page exists anywhere in the codebase (the existing `MagicLink` verify flow is a pure REST/JSON endpoint, not a page a human fills out) — Task 8 creates one from scratch, following `templates/login.html`'s existing Bootstrap card pattern for visual consistency.

---

## Task 1: Fix `AuthService`'s duplicate `UserRepository`

Small, self-contained cleanup done first since Task 6 onward makes `AuthService.registerUser` central to every invitation-acceptance path — better to build on the correct foundation.

**Files:**
- Modify: `src/main/java/org/confcms/cms/auth/service/AuthService.java`
- Delete: `src/main/java/org/confcms/cms/auth/repository/UserRepository.java`

- [ ] **Step 1: Confirm `AuthService` is the only importer of the duplicate**

Run: `grep -rln "auth\.repository\.UserRepository\|import org.confcms.cms.auth.repository.UserRepository" src/main/java --include="*.java"`
Expected: only `src/main/java/org/confcms/cms/auth/service/AuthService.java`. If anything else shows up, stop and investigate before proceeding.

- [ ] **Step 2: Repoint `AuthService` to the canonical repository**

In `src/main/java/org/confcms/cms/auth/service/AuthService.java`, change:

```java
import org.confcms.cms.auth.repository.UserRepository;
```

to:

```java
import org.confcms.cms.repository.UserRepository;
```

No other change needed in this file — the canonical `repository.UserRepository` has the same `findByEmail` method the duplicate had.

- [ ] **Step 3: Delete the duplicate repository**

```bash
git rm src/main/java/org/confcms/cms/auth/repository/UserRepository.java
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: repoint AuthService to canonical UserRepository, delete duplicate

auth.repository.UserRepository was a leftover duplicate bean
(bean name authUserRepository) over the same User table as the
canonical repository.UserRepository used everywhere else. Confirmed
AuthService was its only importer. Fixed now since this feature makes
AuthService.registerUser central to every invitation-acceptance path
(Tasks 6+)."
```

---

## Task 2: `ReviewDecline` entity and repository

**Files:**
- Create: `src/main/java/org/confcms/cms/review/domain/ReviewDecline.java`
- Create: `src/main/java/org/confcms/cms/review/repository/ReviewDeclineRepository.java`

- [ ] **Step 1: Create the `ReviewDecline` entity**

Create `src/main/java/org/confcms/cms/review/domain/ReviewDecline.java`:

```java
package org.confcms.cms.review.domain;

import org.confcms.cms.core.domain.BaseEntity;
import org.confcms.cms.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "review_declines")
@Getter
@Setter
public class ReviewDecline extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", nullable = false, unique = true)
    private ReviewAssignment assignment;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suggested_user_id")
    private User suggestedUser;

    private String suggestedName;

    private String suggestedEmail;
}
```

- [ ] **Step 2: Create the repository**

Create `src/main/java/org/confcms/cms/review/repository/ReviewDeclineRepository.java`:

```java
package org.confcms.cms.review.repository;

import org.confcms.cms.review.domain.ReviewDecline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewDeclineRepository extends JpaRepository<ReviewDecline, Long> {
}
```

(No custom finder methods needed yet — `declineAssignment` in Task 4 only ever creates rows via `save`, and nothing reads them back by a specific field in this plan's scope. `JpaRepository`'s inherited `findById` covers `PersonInvitation.suggestedBy` lookups.)

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/confcms/cms/review/domain/ReviewDecline.java src/main/java/org/confcms/cms/review/repository/ReviewDeclineRepository.java
git commit -m "feat: add ReviewDecline entity for reviewer-decline audit trail

Child of ReviewAssignment: required reason, plus a suggested
replacement that is either an existing User or an external name+email
(exactly one -- enforced at the service layer in Task 4, not the DB)."
```

---

## Task 3: `PersonInvitation` entity, enums, and repository

**Files:**
- Create: `src/main/java/org/confcms/cms/domain/InvitationPurpose.java`
- Create: `src/main/java/org/confcms/cms/domain/InvitationStatus.java`
- Create: `src/main/java/org/confcms/cms/domain/PersonInvitation.java`
- Create: `src/main/java/org/confcms/cms/repository/PersonInvitationRepository.java`

- [ ] **Step 1: Create the enums**

Create `src/main/java/org/confcms/cms/domain/InvitationPurpose.java`:

```java
package org.confcms.cms.domain;

public enum InvitationPurpose {
    REVIEWER_SUGGESTION,
    CO_AUTHOR,
    REVIEWER_RECRUITMENT
}
```

Create `src/main/java/org/confcms/cms/domain/InvitationStatus.java`:

```java
package org.confcms.cms.domain;

public enum InvitationStatus {
    PENDING_APPROVAL,
    INVITED,
    ACCEPTED,
    REJECTED
}
```

- [ ] **Step 2: Create the `PersonInvitation` entity**

Create `src/main/java/org/confcms/cms/domain/PersonInvitation.java`:

```java
package org.confcms.cms.domain;

import org.confcms.cms.core.domain.BaseEntity;
import org.confcms.cms.review.domain.ReviewDecline;
import org.confcms.cms.submission.domain.Paper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "person_invitations")
@Getter
@Setter
public class PersonInvitation extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvitationPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvitationStatus status;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean used = false;

    @Column(nullable = false)
    private int resendCount = 0;

    private LocalDateTime lastSentAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_id")
    private Paper paper;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_decline_id")
    private ReviewDecline suggestedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by_user_id")
    private User invitedBy;
}
```

- [ ] **Step 3: Create the repository**

Create `src/main/java/org/confcms/cms/repository/PersonInvitationRepository.java`:

```java
package org.confcms.cms.repository;

import org.confcms.cms.domain.PersonInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PersonInvitationRepository extends JpaRepository<PersonInvitation, Long> {
    Optional<PersonInvitation> findByToken(String token);
    List<PersonInvitation> findByConferenceId(Long conferenceId);
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/domain/InvitationPurpose.java src/main/java/org/confcms/cms/domain/InvitationStatus.java src/main/java/org/confcms/cms/domain/PersonInvitation.java src/main/java/org/confcms/cms/repository/PersonInvitationRepository.java
git commit -m "feat: add PersonInvitation entity for generalized invite mechanism

Covers three trigger origins (REVIEWER_SUGGESTION, CO_AUTHOR,
REVIEWER_RECRUITMENT) converging on one acceptance flow. Own token/
expiresAt/used fields rather than reusing MagicLink, whose user field
is NOT NULL by design (a login mechanism for existing accounts) --
an invitee doesn't have a User yet at invitation time."
```

---

## Task 4: `ReviewAssignmentService.declineAssignment`

**Files:**
- Modify: `src/main/java/org/confcms/cms/review/service/ReviewAssignmentService.java`
- Test: `src/test/java/org/confcms/cms/review/service/ReviewAssignmentServiceTest.java` (extends the existing file)

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/org/confcms/cms/review/service/ReviewAssignmentServiceTest.java` (the file already exists from the committee-roles plan — add these as new `@Test` methods and the new mock/field setup below, don't replace the existing tests):

First, add these fields and imports at the top of the class (alongside the existing `@Mock` fields):

```java
import org.confcms.cms.domain.PersonInvitation;
import org.confcms.cms.review.domain.AssignmentStatus;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.domain.ReviewDecline;
import org.confcms.cms.review.repository.ReviewDeclineRepository;
import org.confcms.cms.service.PersonInvitationService;

import static org.mockito.Mockito.*;
```

```java
    @Mock
    private ReviewDeclineRepository reviewDeclineRepository;
    @Mock
    private PersonInvitationService personInvitationService;
```

Update the `service = new ReviewAssignmentService(...)` line in `setUp()` (matching `ReviewAssignmentService`'s current field order — `assignmentRepository, userRepository, paperRepository, bidRepository, committeeService` — with the two new fields appended after `committeeService` in Step 3 below) to read exactly:

```java
        service = new ReviewAssignmentService(assignmentRepository, userRepository, paperRepository, bidRepository, committeeService, reviewDeclineRepository, personInvitationService);
```

Add these test methods:

```java
    @Test
    void declineAssignmentRejectsBlankReason() {
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setId(50L);
        User reviewer = new User();
        reviewer.setId(20L);
        assignment.setReviewer(reviewer);
        assignment.setPaper(paper);

        when(assignmentRepository.findById(50L)).thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> service.declineAssignment(reviewer, 50L, "  ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void declineAssignmentRejectsWhenNoSuggestionProvided() {
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setId(50L);
        User reviewer = new User();
        reviewer.setId(20L);
        assignment.setReviewer(reviewer);
        assignment.setPaper(paper);

        when(assignmentRepository.findById(50L)).thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> service.declineAssignment(reviewer, 50L, "too busy", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void declineAssignmentRejectsWhenBothSuggestionFormsProvided() {
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setId(50L);
        User reviewer = new User();
        reviewer.setId(20L);
        assignment.setReviewer(reviewer);
        assignment.setPaper(paper);
        User existingSuggestion = new User();
        existingSuggestion.setId(60L);

        when(assignmentRepository.findById(50L)).thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> service.declineAssignment(reviewer, 50L, "too busy", 60L, "Jane Doe", "jane@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void declineAssignmentRejectsNonOwningReviewer() {
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setId(50L);
        User actualReviewer = new User();
        actualReviewer.setId(20L);
        assignment.setReviewer(actualReviewer);
        assignment.setPaper(paper);

        User stranger = new User();
        stranger.setId(99L);

        when(assignmentRepository.findById(50L)).thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> service.declineAssignment(stranger, 50L, "too busy", null, "Jane Doe", "jane@example.com"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void declineAssignmentWithExistingUserSuggestionCreatesNoInvitation() {
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setId(50L);
        User reviewer = new User();
        reviewer.setId(20L);
        assignment.setReviewer(reviewer);
        assignment.setPaper(paper);
        assignment.setStatus(AssignmentStatus.PENDING);

        User existingSuggestion = new User();
        existingSuggestion.setId(60L);

        when(assignmentRepository.findById(50L)).thenReturn(Optional.of(assignment));
        when(userRepository.findById(60L)).thenReturn(Optional.of(existingSuggestion));
        when(reviewDeclineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.declineAssignment(reviewer, 50L, "too busy", 60L, null, null);

        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.DECLINED);
        verify(personInvitationService, never()).inviteCoAuthor(any(), any());
        verifyNoInteractions(personInvitationService);
    }

    @Test
    void declineAssignmentWithExternalSuggestionCreatesReviewerSuggestionInvitation() {
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setId(50L);
        User reviewer = new User();
        reviewer.setId(20L);
        assignment.setReviewer(reviewer);
        assignment.setPaper(paper);
        assignment.setStatus(AssignmentStatus.PENDING);

        when(assignmentRepository.findById(50L)).thenReturn(Optional.of(assignment));
        when(reviewDeclineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.declineAssignment(reviewer, 50L, "too busy", null, "Jane Doe", "jane@example.com");

        assertThat(assignment.getStatus()).isEqualTo(AssignmentStatus.DECLINED);
        verify(personInvitationService).createReviewerSuggestionInvitation(any(ReviewDecline.class), eq(conference), eq("Jane Doe"), eq("jane@example.com"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.review.service.ReviewAssignmentServiceTest"`
Expected: FAILS to compile — `ReviewAssignmentService`'s constructor doesn't take `ReviewDeclineRepository`/`PersonInvitationService` yet, `declineAssignment` doesn't exist, `PersonInvitationService.createReviewerSuggestionInvitation` doesn't exist.

- [ ] **Step 3: Add `declineAssignment` to `ReviewAssignmentService`**

In `src/main/java/org/confcms/cms/review/service/ReviewAssignmentService.java`, add the imports:

```java
import org.confcms.cms.review.domain.ReviewDecline;
import org.confcms.cms.review.repository.ReviewDeclineRepository;
import org.confcms.cms.service.PersonInvitationService;
```

Add the two new fields (Lombok's `@RequiredArgsConstructor` will pick them up — this changes the constructor's parameter list, which is why the test's `setUp()` in Step 1 must pass them in the same order they're declared):

```java
    private final ReviewDeclineRepository reviewDeclineRepository;
    private final PersonInvitationService personInvitationService;
```

Add the method (place it after `submitBid`, before `autoAssignReviewers`):

```java
    @Transactional
    public ReviewDecline declineAssignment(User actingUser, Long assignmentId, String reason,
                                            Long suggestedUserId, String suggestedName, String suggestedEmail) {
        ReviewAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        if (!assignment.getReviewer().getId().equals(actingUser.getId())) {
            throw new SecurityException("Not authorized to decline this assignment");
        }

        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to decline an assignment");
        }

        boolean hasExistingUserSuggestion = suggestedUserId != null;
        boolean hasExternalSuggestion = suggestedName != null && !suggestedName.isBlank()
                && suggestedEmail != null && !suggestedEmail.isBlank();

        if (hasExistingUserSuggestion == hasExternalSuggestion) {
            throw new IllegalArgumentException(
                    "Exactly one of an existing reviewer or a name+email suggestion is required");
        }

        assignment.setStatus(AssignmentStatus.DECLINED);
        assignmentRepository.save(assignment);

        ReviewDecline decline = new ReviewDecline();
        decline.setAssignment(assignment);
        decline.setReason(reason);

        if (hasExistingUserSuggestion) {
            User suggestedUser = userRepository.findById(suggestedUserId)
                    .orElseThrow(() -> new IllegalArgumentException("Suggested reviewer not found"));
            decline.setSuggestedUser(suggestedUser);
        } else {
            decline.setSuggestedName(suggestedName);
            decline.setSuggestedEmail(suggestedEmail);
        }

        ReviewDecline saved = reviewDeclineRepository.save(decline);

        if (hasExternalSuggestion) {
            personInvitationService.createReviewerSuggestionInvitation(
                    saved, assignment.getPaper().getConference(), suggestedName, suggestedEmail);
        }

        return saved;
    }
```

- [ ] **Step 4: Add a placeholder `createReviewerSuggestionInvitation` method to unblock compilation**

This will be fully implemented in Task 6, but `ReviewAssignmentService` needs `PersonInvitationService` to exist to compile. Create a minimal `src/main/java/org/confcms/cms/service/PersonInvitationService.java`:

```java
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
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.review.service.ReviewAssignmentServiceTest"`
Expected: PASS (all tests, including the pre-existing two from the committee-roles plan and the six new ones — none of the new tests actually invoke the placeholder's `throw`, since Mockito mocks `personInvitationService` in the test rather than using the real implementation).

- [ ] **Step 6: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. (`AdminConferenceControllerTest`, `DecisionServiceTest`, and any other test that constructs `ReviewAssignmentService` directly would break here if one existed — confirmed via `grep -rln "new ReviewAssignmentService(" src/test` that `ReviewAssignmentServiceTest` is the only such file.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/review/service/ReviewAssignmentService.java src/main/java/org/confcms/cms/service/PersonInvitationService.java src/test/java/org/confcms/cms/review/service/ReviewAssignmentServiceTest.java
git commit -m "feat: add ReviewAssignmentService.declineAssignment

Requires the acting user to own the assignment (ownership check,
same IDOR-prevention pattern as the earlier submitReview fix),
a non-blank reason, and exactly one suggestion form (existing user
XOR external name+email). External suggestions call the new
PersonInvitationService (implemented in Task 6; stubbed here to
unblock compilation) to create a chair-gated invitation."
```

---

## Task 5: `CommitteeService.hasAnyCommitteeRole`

**Files:**
- Modify: `src/main/java/org/confcms/cms/service/CommitteeService.java`
- Modify: `src/main/java/org/confcms/cms/repository/ConferenceCommitteeRoleRepository.java`
- Test: `src/test/java/org/confcms/cms/service/CommitteeServiceTest.java` (extends the existing file)

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/org/confcms/cms/service/CommitteeServiceTest.java` (existing file from the committee-roles plan):

```java
    @Test
    void hasAnyCommitteeRoleTrueWhenAnyRoleExists() {
        when(repository.existsByConferenceIdAndUserId(1L, 10L)).thenReturn(true);

        assertThat(service.hasAnyCommitteeRole(user, conference)).isTrue();
    }

    @Test
    void hasAnyCommitteeRoleFalseWhenNoRoleExists() {
        when(repository.existsByConferenceIdAndUserId(1L, 10L)).thenReturn(false);

        assertThat(service.hasAnyCommitteeRole(user, conference)).isFalse();
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.service.CommitteeServiceTest"`
Expected: FAILS to compile — `hasAnyCommitteeRole` and `existsByConferenceIdAndUserId` don't exist yet.

- [ ] **Step 3: Add the repository method**

In `src/main/java/org/confcms/cms/repository/ConferenceCommitteeRoleRepository.java`, add:

```java
    boolean existsByConferenceIdAndUserId(Long conferenceId, Long userId);
```

- [ ] **Step 4: Add the service method**

In `src/main/java/org/confcms/cms/service/CommitteeService.java`, add after `isChairOrCoChair`:

```java
    public boolean hasAnyCommitteeRole(User user, Conference conference) {
        return repository.existsByConferenceIdAndUserId(conference.getId(), user.getId());
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.service.CommitteeServiceTest"`
Expected: PASS (all tests, including the 5 pre-existing ones).

- [ ] **Step 6: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/service/CommitteeService.java src/main/java/org/confcms/cms/repository/ConferenceCommitteeRoleRepository.java src/test/java/org/confcms/cms/service/CommitteeServiceTest.java
git commit -m "feat: add CommitteeService.hasAnyCommitteeRole

Answers 'does this user hold any committee role on this conference,
regardless of which one' -- needed for reviewer-recruitment
authorization (Task 6), where any REVIEWER/CHAIR/CO_CHAIR should be
able to recruit, not just Chair/Co-Chair."
```

---

## Task 6: `PersonInvitationService` — full implementation

**Files:**
- Modify: `src/main/java/org/confcms/cms/service/PersonInvitationService.java` (replaces the Task 4 placeholder)
- Test: `src/test/java/org/confcms/cms/service/PersonInvitationServiceTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/confcms/cms/service/PersonInvitationServiceTest.java`:

```java
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.service.PersonInvitationServiceTest"`
Expected: FAILS to compile — the placeholder `PersonInvitationService` doesn't have any of these methods/constructor shape yet.

- [ ] **Step 3: Add `assignReviewerFromInvitation` to `ReviewAssignmentService`**

`PersonInvitationService.acceptInvitation` needs to create the actual `ReviewAssignment` once a `REVIEWER_SUGGESTION` invitee registers. Add a small public wrapper in `src/main/java/org/confcms/cms/review/service/ReviewAssignmentService.java` (after `assignReviewerInternal`, which is already `private` and does exactly this insert — this new method is the authorization-free public entry point for the invitation-acceptance case, where there's no "acting user" performing an authorized action, just the system completing an already-approved flow):

```java
    @Transactional
    public void assignReviewerFromInvitation(Paper paper, User reviewer) {
        assignReviewerInternal(paper, reviewer);
    }
```

- [ ] **Step 4: Implement the full `PersonInvitationService`**

Replace the contents of `src/main/java/org/confcms/cms/service/PersonInvitationService.java`:

```java
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
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
        requireChairOrAdmin(actingUser, invitation.getConference());

        invitation.setStatus(InvitationStatus.REJECTED);
        return repository.save(invitation);
    }

    @Transactional
    public PersonInvitation resend(User actingUser, Long invitationId) {
        PersonInvitation invitation = repository.findById(invitationId)
                .orElseThrow(() -> new IllegalArgumentException("Invitation not found"));

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
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.service.PersonInvitationServiceTest"`
Expected: PASS (15 tests).

- [ ] **Step 6: Re-run `ReviewAssignmentServiceTest`**

`ReviewAssignmentService` gained a new public method in Step 3 above. Run: `./gradlew test --tests "org.confcms.cms.review.service.ReviewAssignmentServiceTest"`
Expected: PASS (no change expected, but confirms the new method didn't break anything already tested).

- [ ] **Step 7: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/confcms/cms/service/PersonInvitationService.java src/main/java/org/confcms/cms/review/service/ReviewAssignmentService.java src/test/java/org/confcms/cms/service/PersonInvitationServiceTest.java
git commit -m "feat: full PersonInvitationService implementation

approve/reject gated by Chair/Co-Chair/Admin, REVIEWER_SUGGESTION only.
resend authority varies by purpose (chair/suggester for
REVIEWER_SUGGESTION, submitting author for CO_AUTHOR, original
inviter for REVIEWER_RECRUITMENT; ADMIN always) and is rate-limited
(max 5 resends, minimum 60 minutes between sends) -- enforced in the
service, not just the UI, per the stakeholder's explicit misuse
concern. acceptInvitation creates the User via AuthService with the
correct role per purpose and, for REVIEWER_SUGGESTION, creates the
actual ReviewAssignment via the new
ReviewAssignmentService.assignReviewerFromInvitation. CO_AUTHOR and
REVIEWER_RECRUITMENT skip the approval gate entirely, going straight
to INVITED, matching the design's lower-trust-bar reasoning for both."
```

---

## Task 7: `SubmissionService` co-author invitation hook

**Files:**
- Modify: `src/main/java/org/confcms/cms/submission/service/SubmissionService.java`
- Test: `src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java` (extends the existing file)

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java` (existing file from the domain-model-merge plan):

Add this import and field:

```java
import org.confcms.cms.service.PersonInvitationService;
import org.confcms.cms.submission.domain.PaperAuthor;

import java.util.List;
```

```java
    @Mock
    private PersonInvitationService personInvitationService;
```

Update the `SubmissionService service = new SubmissionService(...)` constructor call in the existing test to pass the new mock (append after `conferenceService`, matching the field declaration order set in Step 2 below).

Add these test methods:

```java
    @Test
    void submitPaperInvitesUnknownCoAuthor() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        Conference activeConference = new Conference();
        activeConference.setTitle("Test Conf");
        activeConference.setVenue("Test Venue");
        activeConference.setStartDate(LocalDate.now());
        activeConference.setEndDate(LocalDate.now().plusDays(1));
        when(conferenceService.getActiveConference()).thenReturn(activeConference);

        User submitter = new User();
        submitter.setFullName("Jane Author");
        submitter.setEmail("jane@example.com");

        when(fileStorageService.store(any())).thenReturn("/uploads/paper.pdf");
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        PaperAuthor coAuthor = new PaperAuthor();
        coAuthor.setFullName("Unknown CoAuthor");
        coAuthor.setEmail("unknown@example.com");
        coAuthor.setAffiliation("Some University");

        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "%PDF-1.4".getBytes());

        service.submitPaper(submitter, "Title", "Abstract", "Track A", file, List.of(coAuthor));

        verify(personInvitationService).inviteCoAuthor(any(), eq(coAuthor));
    }

    @Test
    void submitPaperDoesNotInviteKnownCoAuthor() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        Conference activeConference = new Conference();
        activeConference.setTitle("Test Conf");
        activeConference.setVenue("Test Venue");
        activeConference.setStartDate(LocalDate.now());
        activeConference.setEndDate(LocalDate.now().plusDays(1));
        when(conferenceService.getActiveConference()).thenReturn(activeConference);

        User submitter = new User();
        submitter.setFullName("Jane Author");
        submitter.setEmail("jane@example.com");

        when(fileStorageService.store(any())).thenReturn("/uploads/paper.pdf");
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User existingCoAuthor = new User();
        when(userRepository.findByEmail("known@example.com")).thenReturn(Optional.of(existingCoAuthor));

        PaperAuthor coAuthor = new PaperAuthor();
        coAuthor.setFullName("Known CoAuthor");
        coAuthor.setEmail("known@example.com");
        coAuthor.setAffiliation("Some University");

        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "%PDF-1.4".getBytes());

        service.submitPaper(submitter, "Title", "Abstract", "Track A", file, List.of(coAuthor));

        verify(personInvitationService, never()).inviteCoAuthor(any(), any());
    }
```

Add the needed static imports if not already present in the file: `import static org.mockito.Mockito.verify;`, `import static org.mockito.Mockito.never;`, `import static org.mockito.ArgumentMatchers.eq;`, `import java.util.Optional;`, and `import org.confcms.cms.repository.UserRepository;`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.submission.service.SubmissionServiceTest"`
Expected: FAILS to compile — `SubmissionService`'s constructor doesn't take `PersonInvitationService`/`UserRepository` yet.

- [ ] **Step 3: Add the hook to `SubmissionService`**

In `src/main/java/org/confcms/cms/submission/service/SubmissionService.java`, add imports:

```java
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.PersonInvitationService;
```

Add the two new fields:

```java
    private final PersonInvitationService personInvitationService;
    private final UserRepository userRepository;
```

In `submitPaper`, immediately after the existing author-saving loop (`for (PaperAuthor author : authors) { ... }`) and before `Paper saved = paperRepository.save(paper);`, add:

```java
        // Invite any co-author who isn't already a registered User
        for (PaperAuthor author : authors) {
            if (userRepository.findByEmail(author.getEmail()).isEmpty()) {
                personInvitationService.inviteCoAuthor(paper, author);
            }
        }
```

Note: this iterates `authors` (the method parameter) again rather than `paper.getAuthors()`, since at this point in the method `paper` hasn't been persisted yet and either list is equivalent — using the original parameter avoids any ambiguity about whether `paper.getAuthors()` is populated before save.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.submission.service.SubmissionServiceTest"`
Expected: PASS (3 tests: the original from the domain-model-merge plan, plus the two new ones).

- [ ] **Step 5: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. (Check for any other test constructing `SubmissionService` directly: `grep -rln "new SubmissionService(" src/test` — expected to be only `SubmissionServiceTest`.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/confcms/cms/submission/service/SubmissionService.java src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java
git commit -m "feat: auto-invite unknown co-authors at paper submission

For each PaperAuthor whose email doesn't match an existing User,
calls PersonInvitationService.inviteCoAuthor -- no chair approval
needed, since the submitting author is vouching for their own
co-author. Runs inside the same @Transactional submitPaper method,
so a submission with an author who can't be invited rolls back
entirely rather than half-succeeding."
```

---

## Task 8: Invitation-acceptance controller and page

**Files:**
- Create: `src/main/java/org/confcms/cms/web/controller/PersonInvitationController.java`
- Create: `src/main/resources/templates/invitations/accept.html`
- Modify: `src/main/java/org/confcms/cms/config/SecurityConfig.java`
- Modify: `src/main/java/org/confcms/cms/config/DevSecurityConfig.java`

- [ ] **Step 1: Create the controller**

Create `src/main/java/org/confcms/cms/web/controller/PersonInvitationController.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.domain.PersonInvitation;
import org.confcms.cms.repository.PersonInvitationRepository;
import org.confcms.cms.service.PersonInvitationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/invitations")
@RequiredArgsConstructor
public class PersonInvitationController {

    private final PersonInvitationService personInvitationService;
    private final PersonInvitationRepository personInvitationRepository;

    @GetMapping("/accept")
    public String showAcceptForm(@RequestParam String token, Model model) {
        PersonInvitation invitation = personInvitationRepository.findByToken(token).orElse(null);
        if (invitation == null || invitation.isUsed() || invitation.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
            model.addAttribute("error", "This invitation link is invalid or has expired.");
            return "invitations/accept";
        }
        model.addAttribute("token", token);
        model.addAttribute("invitation", invitation);
        return "invitations/accept";
    }

    @PostMapping("/accept")
    public String processAccept(@RequestParam String token, @RequestParam String password, Model model) {
        try {
            personInvitationService.acceptInvitation(token, password);
            return "redirect:/login?invitationAccepted=true";
        } catch (IllegalArgumentException iae) {
            model.addAttribute("error", iae.getMessage());
            model.addAttribute("token", token);
            return "invitations/accept";
        }
    }
}
```

Add the missing `@RequestMapping` import (needed at class level): add `import org.springframework.web.bind.annotation.RequestMapping;` to the import list above.

- [ ] **Step 2: Create the acceptance page template**

Create `src/main/resources/templates/invitations/accept.html`, following `templates/login.html`'s Bootstrap card pattern:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head>
    <meta charset="UTF-8">
    <title>Accept Invitation - Conference CMS</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/app.css" rel="stylesheet">
</head>

<body class="bg-light">
    <div class="container">
        <div class="row justify-content-center mt-5">
            <div class="col-md-6">
                <div class="card shadow">
                    <div class="card-body p-5">
                        <h2 class="text-center mb-4">Accept Invitation</h2>

                        <div th:if="${error}" class="alert alert-danger" th:text="${error}"></div>

                        <div th:if="${invitation != null}">
                            <p>You've been invited to join <strong th:text="${invitation.conference.title}">Conference</strong>
                                as a <span th:text="${invitation.purpose}">ROLE</span>.</p>

                            <form method="post" action="/invitations/accept">
                                <input type="hidden" name="token" th:value="${token}">
                                <div class="mb-3">
                                    <label class="form-label">Your Name</label>
                                    <input type="text" class="form-control" th:value="${invitation.name}" disabled>
                                </div>
                                <div class="mb-3">
                                    <label class="form-label">Email</label>
                                    <input type="email" class="form-control" th:value="${invitation.email}" disabled>
                                </div>
                                <div class="mb-3">
                                    <label for="password" class="form-label">Set a Password</label>
                                    <input type="password" class="form-control" id="password" name="password" required minlength="8">
                                </div>
                                <button type="submit" class="btn btn-primary w-100">Accept & Create Account</button>
                            </form>

                            <!-- OAuth2/magic-link options can be added here once item 2.4 (multi-auth) is built -->
                        </div>

                        <div class="text-center mt-3">
                            <a href="/">Back to Home</a>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>
</body>

</html>
```

- [ ] **Step 3: Add `/invitations/**` to the public route allowlist**

In `src/main/java/org/confcms/cms/config/SecurityConfig.java`, find the `.requestMatchers(...).permitAll()` line (the one already listing `/`, `/about`, `/login`, `/auth/**`, etc.) and add `/invitations/**` to it:

```java
                .requestMatchers("/", "/home", "/about", "/committee", "/speakers", "/schedule", "/venue", "/contact", "/register", "/login", "/magic-link/**", "/auth/**", "/invitations/**").permitAll()
```

Make the identical change in `src/main/java/org/confcms/cms/config/DevSecurityConfig.java`'s matching line.

- [ ] **Step 4: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Manual verification via running app**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` in the background, then `curl -s -o /dev/null -w "HTTP_%{http_code}\n" "http://localhost:8080/invitations/accept?token=nonexistent"` — expect `200` (renders the page with an error message, not a 404/500, since `showAcceptForm` handles the not-found case by setting a model error rather than throwing). Stop the app afterward.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/PersonInvitationController.java src/main/resources/templates/invitations/accept.html src/main/java/org/confcms/cms/config/SecurityConfig.java src/main/java/org/confcms/cms/config/DevSecurityConfig.java
git commit -m "feat: invitation-acceptance page and public route

New /invitations/accept GET (show form) and POST (process) endpoints,
public (no auth required, matching /auth/** and /login's existing
pattern). Password-only today, with an explicit comment marking where
OAuth2/magic-link options slot in once the separate multi-auth feature
(evaluation doc item 2.4) is built -- deliberately not blocking this
feature on that larger, unbuilt scope."
```

---

## Task 9: Decline, approve/reject/resend, and recruit endpoints

**Files:**
- Modify: `src/main/java/org/confcms/cms/web/controller/ReviewRestController.java`
- Create: `src/main/java/org/confcms/cms/web/controller/PersonInvitationAdminController.java`

- [ ] **Step 1: Add the decline endpoint to `ReviewRestController`**

In `src/main/java/org/confcms/cms/web/controller/ReviewRestController.java`, add the import:

```java
import org.confcms.cms.review.domain.ReviewDecline;
```

Add after the existing `submitReview` method:

```java
    @PostMapping("/decline")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<?> declineAssignment(@RequestParam Long assignmentId,
                                               @RequestParam String reason,
                                               @RequestParam(required = false) Long suggestedUserId,
                                               @RequestParam(required = false) String suggestedName,
                                               @RequestParam(required = false) String suggestedEmail) {
        try {
            ReviewDecline decline = assignmentService.declineAssignment(actingUser(), assignmentId, reason, suggestedUserId, suggestedName, suggestedEmail);
            return ResponseEntity.ok(decline);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }
```

(Reuses the `actingUser()` private helper already added to this controller in the committee-roles plan.)

- [ ] **Step 2: Create the invitation-management controller**

Create `src/main/java/org/confcms/cms/web/controller/PersonInvitationAdminController.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.PersonInvitation;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.PersonInvitationRepository;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.PersonInvitationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/invitations")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
public class PersonInvitationAdminController {

    private final PersonInvitationService personInvitationService;
    private final PersonInvitationRepository personInvitationRepository;
    private final UserRepository userRepository;

    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }

    @GetMapping("/conference/{conferenceId}")
    public ResponseEntity<?> listForConference(@PathVariable Long conferenceId) {
        List<PersonInvitation> invitations = personInvitationRepository.findByConferenceId(conferenceId);
        return ResponseEntity.ok(invitations);
    }

    @PostMapping("/{invitationId}/approve")
    public ResponseEntity<?> approve(@PathVariable Long invitationId) {
        try {
            return ResponseEntity.ok(personInvitationService.approve(actingUser(), invitationId));
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @PostMapping("/{invitationId}/reject")
    public ResponseEntity<?> reject(@PathVariable Long invitationId, @RequestParam(required = false) String reason) {
        try {
            return ResponseEntity.ok(personInvitationService.reject(actingUser(), invitationId, reason));
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @PostMapping("/{invitationId}/resend")
    public ResponseEntity<?> resend(@PathVariable Long invitationId) {
        try {
            return ResponseEntity.ok(personInvitationService.resend(actingUser(), invitationId));
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        } catch (IllegalStateException ise) {
            return ResponseEntity.status(429).body(ise.getMessage());
        }
    }

    @PostMapping("/recruit")
    public ResponseEntity<?> recruitReviewer(@RequestParam Long conferenceId, @RequestParam String name, @RequestParam String email) {
        Conference conference = new Conference();
        conference.setId(conferenceId);
        try {
            return ResponseEntity.ok(personInvitationService.recruitReviewer(actingUser(), conference, name, email));
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }
}
```

Note on `recruitReviewer`'s controller method: it constructs a `Conference` with only `id` set (a lazy JPA reference pattern), rather than fetching the full entity, since `PersonInvitationService.recruitReviewer` only ever calls `conference.getId()` (via `committeeService.hasAnyCommitteeRole`) and stores the reference on the new `PersonInvitation` (a `@ManyToOne` needs only the ID to persist correctly). If a future caller needs `conference.getTitle()` inside that service method, fetch the real entity via `ConferenceRepository` instead of this shortcut — flagged here rather than silently done, since it's a minor exception to "always use real entities" for a narrow, safe reason.

- [ ] **Step 3: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass (every test class from this plan plus every pre-existing one from the domain-model-merge and committee-roles plans).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/ReviewRestController.java src/main/java/org/confcms/cms/web/controller/PersonInvitationAdminController.java
git commit -m "feat: decline, invitation approve/reject/resend, and recruit endpoints

POST /review/decline (REVIEWER role) submits a decline with reason and
suggestion. New PersonInvitationAdminController exposes
list/approve/reject/resend (rate-limit violations surface as 429) and
a recruit endpoint for any REVIEWER/CHAIR/CO_CHAIR/ADMIN to invite a
new reviewer directly."
```

---

## Task 10: `ConflictDeclaration` entity, repository, service, and endpoint

**Files:**
- Create: `src/main/java/org/confcms/cms/domain/ConflictDeclaration.java`
- Create: `src/main/java/org/confcms/cms/repository/ConflictDeclarationRepository.java`
- Create: `src/main/java/org/confcms/cms/service/ConflictDeclarationService.java`
- Create: `src/main/java/org/confcms/cms/web/controller/ConflictDeclarationController.java`
- Modify: `src/main/java/org/confcms/cms/review/service/ReviewAssignmentService.java`
- Modify: `src/test/java/org/confcms/cms/review/service/ReviewAssignmentServiceTest.java`
- Test: `src/test/java/org/confcms/cms/service/ConflictDeclarationServiceTest.java`

- [ ] **Step 1: Create the entity**

Create `src/main/java/org/confcms/cms/domain/ConflictDeclaration.java`:

```java
package org.confcms.cms.domain;

import org.confcms.cms.core.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "conflict_declarations",
       uniqueConstraints = @UniqueConstraint(columnNames = {"conference_id", "reviewer_id", "declared_against_user_id"}))
@Getter
@Setter
public class ConflictDeclaration extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private User reviewer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "declared_against_user_id", nullable = false)
    private User declaredAgainstUser;
}
```

- [ ] **Step 2: Create the repository**

Create `src/main/java/org/confcms/cms/repository/ConflictDeclarationRepository.java`:

```java
package org.confcms.cms.repository;

import org.confcms.cms.domain.ConflictDeclaration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConflictDeclarationRepository extends JpaRepository<ConflictDeclaration, Long> {
    List<ConflictDeclaration> findByConferenceIdAndReviewerId(Long conferenceId, Long reviewerId);
    Optional<ConflictDeclaration> findByConferenceIdAndReviewerIdAndDeclaredAgainstUserId(Long conferenceId, Long reviewerId, Long declaredAgainstUserId);
    boolean existsByConferenceIdAndReviewerIdAndDeclaredAgainstUserId(Long conferenceId, Long reviewerId, Long declaredAgainstUserId);
}
```

- [ ] **Step 3: Write the failing tests**

Create `src/test/java/org/confcms/cms/service/ConflictDeclarationServiceTest.java`:

```java
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

        service.declareConflict(reviewer, conference, author);

        verify(repository, never()).save(any());
    }

    @Test
    void hasConflictTrueWhenDeclared() {
        when(repository.existsByConferenceIdAndReviewerIdAndDeclaredAgainstUserId(1L, 10L, 20L)).thenReturn(true);

        assertThat(service.hasConflict(reviewer, conference, author)).isTrue();
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.service.ConflictDeclarationServiceTest"`
Expected: FAILS to compile — `ConflictDeclarationService` doesn't exist yet.

- [ ] **Step 5: Implement `ConflictDeclarationService`**

Create `src/main/java/org/confcms/cms/service/ConflictDeclarationService.java`:

```java
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
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.service.ConflictDeclarationServiceTest"`
Expected: PASS (3 tests).

- [ ] **Step 7: Wire the conflict check into `autoAssignReviewers`**

In `src/main/java/org/confcms/cms/review/service/ReviewAssignmentService.java`, add the import:

```java
import org.confcms.cms.service.ConflictDeclarationService;
```

Add the field:

```java
    private final ConflictDeclarationService conflictDeclarationService;
```

In `autoAssignReviewers`, inside the `for (User reviewer : reviewers)` loop, add a conflict check alongside the existing bid-based skip condition. Change:

```java
            // Skip if conflict or not willing
            BidType bid = reviewerBids.get(reviewer.getId());
            if (bid == BidType.CONFLICT || bid == BidType.NOT_WILLING) continue;
```

to:

```java
            // Skip if conflict or not willing
            BidType bid = reviewerBids.get(reviewer.getId());
            if (bid == BidType.CONFLICT || bid == BidType.NOT_WILLING) continue;

            // Skip if the reviewer has declared a conflict against any author on this paper
            boolean hasDeclaredConflict = paper.getAuthors().stream()
                    .anyMatch(paperAuthor -> {
                        var authorUser = userRepository.findByEmail(paperAuthor.getEmail());
                        return authorUser.isPresent()
                                && conflictDeclarationService.hasConflict(reviewer, paper.getConference(), authorUser.get());
                    });
            if (hasDeclaredConflict) continue;
```

- [ ] **Step 7b: Update `ReviewAssignmentServiceTest`'s constructor call for the new field**

`ReviewAssignmentService` now takes an 8th constructor parameter. In `src/test/java/org/confcms/cms/review/service/ReviewAssignmentServiceTest.java`, add the import and mock field:

```java
import org.confcms.cms.service.ConflictDeclarationService;
```

```java
    @Mock
    private ConflictDeclarationService conflictDeclarationService;
```

Update the `setUp()` constructor call (set in Task 4) from:

```java
        service = new ReviewAssignmentService(assignmentRepository, userRepository, paperRepository, bidRepository, committeeService, reviewDeclineRepository, personInvitationService);
```

to:

```java
        service = new ReviewAssignmentService(assignmentRepository, userRepository, paperRepository, bidRepository, committeeService, reviewDeclineRepository, personInvitationService, conflictDeclarationService);
```

Without this change, `./gradlew test` in Step 10 below would fail to compile the test sources — `compileJava` alone (Step 9) would still pass, since it only compiles `src/main`, so this gap would not surface until the full test run.

- [ ] **Step 8: Create the declaration endpoint**

Create `src/main/java/org/confcms/cms/web/controller/ConflictDeclarationController.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.ConflictDeclarationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/review/conflicts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('REVIEWER')")
public class ConflictDeclarationController {

    private final ConflictDeclarationService conflictDeclarationService;
    private final UserRepository userRepository;

    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }

    @PostMapping("/declare")
    public ResponseEntity<?> declare(@RequestParam Long conferenceId, @RequestParam Long declaredAgainstUserId) {
        Conference conference = new Conference();
        conference.setId(conferenceId);
        User declaredAgainstUser = userRepository.findById(declaredAgainstUserId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return ResponseEntity.ok(conflictDeclarationService.declareConflict(actingUser(), conference, declaredAgainstUser));
    }
}
```

(Same narrow lazy-reference pattern for `conferenceId` as Task 9's `recruitReviewer` endpoint, for the same reason: `declareConflict` only ever needs `conference.getId()`.)

- [ ] **Step 9: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/org/confcms/cms/domain/ConflictDeclaration.java src/main/java/org/confcms/cms/repository/ConflictDeclarationRepository.java src/main/java/org/confcms/cms/service/ConflictDeclarationService.java src/main/java/org/confcms/cms/web/controller/ConflictDeclarationController.java src/main/java/org/confcms/cms/review/service/ReviewAssignmentService.java src/test/java/org/confcms/cms/review/service/ReviewAssignmentServiceTest.java src/test/java/org/confcms/cms/service/ConflictDeclarationServiceTest.java
git commit -m "feat: pre-review conflict-of-interest declaration by author identity

ConflictDeclaration records (conference, reviewer, declaredAgainstUser)
-- declared once per pair, idempotent on re-declare. Wired into
autoAssignReviewers as an additional skip condition alongside the
existing CONFLICT/NOT_WILLING bid check, closing the previous
backwards CoI mechanism (which required a reviewer to already be
looking at a specific paper's bid before declaring a conflict)."
```

---

## Task 11: Final full-suite verification

**Files:** none changed — verification only.

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew clean test`
Expected: BUILD SUCCESSFUL, all tests pass across every test class from this plan and both prior plans.

- [ ] **Step 2: Run a full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Start the app and smoke-test**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` in the background.

- Confirm `GET /login` returns 200 (regression check).
- Confirm `GET /invitations/accept?token=nonexistent` returns 200 with an error message rendered (not a 500).
- Confirm `GET /committee` still returns 200 (regression check against the committee-roles feature).

Stop the app afterward. No commit needed for this verification-only task.

---

## Self-review notes (for whoever executes this plan)

- **Task ordering matters for compilation, not just logic.** Task 4 introduces a forward dependency on `PersonInvitationService` before it's fully built (Task 6) via a throwing placeholder — this is intentional, matching the "keep every intermediate state compilable and green" discipline established in the prior two plans, not an oversight.
- **The `recruitReviewer`/`declare` controllers' lazy `Conference` reference pattern** (setting only `id`, not fetching the full entity) is called out explicitly in Tasks 9 and 10 rather than silently done, since it's a narrow exception to "use real entities" that only holds because the downstream service methods never read any other `Conference` field. If a future change makes either method read `conference.getTitle()` or similar, that assumption breaks and the controller must fetch the real entity instead.
- **`resend`'s rate limit (max 5 sends, 60-minute minimum interval)** is a concrete stakeholder requirement ("we need to consider magic link email validity... some people will misuse those features"), not an arbitrary choice — flagged here so it isn't quietly loosened during implementation without re-confirming with the stakeholder.
- **This plan does not touch reviewer specialist-field tagging or filtering**, per the design spec's explicit deferral — that's the next brainstorm, not folded in here.
- **No test framework or dependency setup needed** — confirmed in the prior two plans that `spring-boot-starter-test` (JUnit 5, AssertJ, Mockito) is already available, and this plan introduces no new external dependencies (no new library for token generation — `UUID.randomUUID()` from the JDK, matching the existing `MagicLinkService` pattern exactly).
