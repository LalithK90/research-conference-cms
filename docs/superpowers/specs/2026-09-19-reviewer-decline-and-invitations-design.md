# Reviewer Decline, Person Invitations & Conflict Declaration — Design

**Date:** 2026-09-19
**Status:** Approved, ready for implementation planning.
**Scope:** Implements evaluation-doc items 2.2 (reviewer decline + suggestion), 2.3 (chair-approved auto-invite), and 3.2 (CoI declared by name before de-blinding) — extended during brainstorming to a generalized invitation mechanism covering co-author invites at submission time and reviewer-to-reviewer recruitment, per stakeholder requirements raised in this design session.

**Explicitly deferred to its own future brainstorm:** reviewer specialist-field tagging and field-based filtering during assignment (raised in this session, scoped out as a separate capability — see "Deferred" section below).

---

## Context

Today, `AssignmentStatus.DECLINED` exists as an enum value but nothing sets it — `ReviewAssignmentService` has no decline method at all. `ReviewBid.conflictReason` is the only conflict-of-interest mechanism, and it requires a reviewer to already be looking at a specific paper (backwards from standard practice, which resolves CoI by name/affiliation before any paper is shown). No invitation mechanism exists anywhere in the codebase for bringing new people into the system automatically — `AuthRestController.register` is public self-registration only. `PaperAuthor` already stores `email` but has no link to `User` — an author's co-authors are just contact-info records today, with no path to those co-authors becoming actual system participants.

Also found during this session's investigation: `AuthService` (used by public self-registration) depends on a duplicate `auth.repository.UserRepository` bean, left over from the earlier domain-model-merge work rather than the canonical `repository.UserRepository` used everywhere else in the codebase. Since this feature makes `AuthService.registerUser` central to every invitation-acceptance path, this duplicate is fixed as part of this work rather than propagated further.

## Goals

- A reviewer declining an assignment must give a reason and suggest a replacement (existing user, or an external person by name+email) — both required, per prior confirmed requirement.
- If the suggested replacement is an external person, nothing is emailed until a Chair/Co-Chair (or Admin) approves — a hard requirement confirmed earlier in this project's design work.
- When a paper is submitted and a listed co-author's email doesn't match an existing `User`, that co-author is automatically invited to the system — no chair approval needed, since the submitting author is vouching for them.
- Any existing reviewer (not just Chair/Co-Chair/Admin) can invite a new person to join as a reviewer for a conference.
- Stale/unaccepted invitations can be resent by an appropriately-authorized person, with abuse-resistant rate limiting (explicit stakeholder concern: magic-link-style invite mechanisms are prone to misuse if unbounded).
- A rejected reviewer-suggestion invitation surfaces an immediate "assign someone else" action rather than leaving the paper silently short a reviewer.
- Reviewers can declare conflicts of interest against conference authors' names/affiliations before ever seeing paper content, closing the current backwards (bid-time-only) CoI mechanism.

## Non-goals (explicitly deferred)

- **Reviewer specialist fields + filtering by field during assignment.** Raised in this session as a real need (avoid assigning a robotics paper to a social-science reviewer), and the currently-orphaned `SubTheme` entity (conference-scoped, exists, but referenced by nothing else in the codebase) is the natural foundation for it. Deferred to its own brainstorm because it touches `User` profile shape, `SubTheme`'s actual wiring, and `ReviewAssignmentService`'s scoring/filtering logic — real design surface of its own, not a corner of this feature.
- **The full multi-auth rebuild** (password+forgot-password, ORCID OAuth2, Google OAuth2, magic link as the complete login surface — evaluation-doc item 2.4). This feature's invitation-acceptance page is built password-only today, deliberately structured so OAuth2/magic-link options can be added to the same acceptance page later without restructuring `PersonInvitation`.
- **`ConflictDeclaration`'s interaction with double-blind anonymization** (evaluation-doc item 3.1, also not yet built) — the design below assumes reviewers can see author names/affiliations for CoI-declaration purposes even under a future blind-review mode, which is standard practice (CoI declaration is explicitly the one place where seeing author identity is necessary and safe, since it happens before paper content is shown) — noted here so 3.1's future design doesn't contradict this.

---

## 1. Data Model

### `ReviewDecline`

New entity, child of `ReviewAssignment`:

```java
@Entity
@Table(name = "review_declines")
public class ReviewDecline extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", nullable = false, unique = true)
    private ReviewAssignment assignment;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suggested_user_id")
    private User suggestedUser; // nullable

    private String suggestedName;  // nullable
    private String suggestedEmail; // nullable

    // Invariant enforced at the service layer, not the DB: exactly one of
    // suggestedUser OR (suggestedName AND suggestedEmail) is set.
}
```

### `PersonInvitation`

New entity, generalized across three trigger origins:

```java
public enum InvitationPurpose { REVIEWER_SUGGESTION, CO_AUTHOR, REVIEWER_RECRUITMENT }
public enum InvitationStatus { PENDING_APPROVAL, INVITED, ACCEPTED, REJECTED }

@Entity
@Table(name = "person_invitations")
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
    private Paper paper; // set for CO_AUTHOR only

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_decline_id")
    private ReviewDecline suggestedBy; // set for REVIEWER_SUGGESTION only

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invited_by_user_id")
    private User invitedBy; // nullable -- set for REVIEWER_RECRUITMENT (the recruiting reviewer);
                             // for REVIEWER_SUGGESTION, set to the approving chair once approved,
                             // not the original suggesting reviewer (that's reachable via suggestedBy.assignment.reviewer)
}
```

**Why a separate token/expiry, not reusing `MagicLink`:** `MagicLink.user` is `NOT NULL` by design — it is a login mechanism for people who already have an account. An invitee doesn't have a `User` yet at invitation time, so `PersonInvitation` carries its own `token`/`expiresAt`/`used` fields, following the exact same proven shape as `MagicLink`, without weakening that existing entity's invariant for an unrelated purpose.

**Status flow by purpose:**
- `REVIEWER_SUGGESTION`: created at `PENDING_APPROVAL` → Chair/Co-Chair/Admin `approve()`s (→ `INVITED`, email sent) or `reject()`s (→ `REJECTED`).
- `CO_AUTHOR`: created directly at `INVITED` (email sent immediately) — no approval step.
- `REVIEWER_RECRUITMENT`: created directly at `INVITED` (email sent immediately) — no approval step.
- All three converge on `ACCEPTED` via the same `acceptInvitation(token, password)` flow.

### `ConflictDeclaration`

New entity:

```java
@Entity
@Table(name = "conflict_declarations",
       uniqueConstraints = @UniqueConstraint(columnNames = {"conference_id", "reviewer_id", "declared_against_user_id"}))
public class ConflictDeclaration extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private User reviewer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "declared_against_user_id", nullable = false)
    private User declaredAgainstUser; // an author on this conference's papers
}
```

Populated from a one-time-per-reviewer-per-conference "declare conflicts" screen listing every distinct author (name + affiliation, no paper titles/abstracts) across the conference's submitted papers — resolvable to a `User` since `PaperAuthor.email` can be matched against `UserRepository.findByEmail`; an author with no matching `User` yet (not registered) is simply not declarable against until 2.3's co-author invitation brings them into the system, which is an acceptable ordering gap, not a design flaw.

---

## 2. Authorization & Service Layer

### `ReviewAssignmentService.declineAssignment`

```java
@Transactional
public ReviewDecline declineAssignment(User actingUser, Long assignmentId, String reason,
                                        Long suggestedUserId, String suggestedName, String suggestedEmail) {
    // 1. Load assignment; verify actingUser IS the assignment's reviewer (ownership check,
    //    same pattern as the ReviewService.submitReview IDOR fix from the domain-model work).
    // 2. Validate: reason non-blank; exactly one of suggestedUserId OR (suggestedName+suggestedEmail) present.
    // 3. Set assignment.status = DECLINED.
    // 4. Create ReviewDecline.
    // 5. If suggestedUserId is null (external suggestion): create PersonInvitation(purpose=REVIEWER_SUGGESTION,
    //    status=PENDING_APPROVAL, conference=assignment.paper.conference, suggestedBy=the new ReviewDecline).
    //    If suggestedUserId resolves to an existing User, no invitation is created -- just recorded on ReviewDecline
    //    for the chair's visibility when assigning a replacement manually.
}
```

### `PersonInvitationService`

```java
approve(User actingUser, Long invitationId)
    // REVIEWER_SUGGESTION only. actingUser must be ADMIN or isChairOrCoChair(conference).
    // -> status=INVITED, invitedBy=actingUser, generate token/expiresAt, send email, lastSentAt=now.

reject(User actingUser, Long invitationId, String reason)
    // REVIEWER_SUGGESTION only. Same authority as approve.
    // -> status=REJECTED. No email sent to the rejected candidate (avoids an awkward
    //    "you were suggested but rejected" message -- the suggesting reviewer/chair
    //    handles this offline if they choose).

resend(User actingUser, Long invitationId)
    // Authority depends on purpose:
    //   REVIEWER_SUGGESTION: ADMIN, isChairOrCoChair(conference), OR the original
    //     suggesting reviewer (invitation.suggestedBy.assignment.reviewer).
    //   CO_AUTHOR: ADMIN, or the submitting author (invitation.paper.submitter).
    //   REVIEWER_RECRUITMENT: ADMIN, or invitation.invitedBy.
    // Rate limit (enforced here, not just in the UI): reject if resendCount >= 5, or if
    // lastSentAt is within the last hour. Otherwise: new token, new expiresAt, resendCount++,
    // lastSentAt=now, re-send email.

acceptInvitation(String token, String password)
    // Public, no auth. Reject if token unknown, used, or expired (IllegalArgumentException ->
    // 400, not a stack trace). Creates User via the now-fixed AuthService.registerUser(email,
    // password, name, role) where role = REVIEWER for REVIEWER_SUGGESTION/REVIEWER_RECRUITMENT,
    // AUTHOR for CO_AUTHOR. Marks invitation used=true, status=ACCEPTED. For REVIEWER_SUGGESTION,
    // also creates the actual ReviewAssignment for the paper the decline originated from.

inviteCoAuthor(Paper paper, PaperAuthor author)
    // Internal, called by SubmissionService. No approval gate. Creates
    // PersonInvitation(purpose=CO_AUTHOR, status=INVITED, paper=paper,
    // conference=paper.conference, name=author.fullName, email=author.email), sends email
    // immediately, lastSentAt=now.

recruitReviewer(User actingUser, Conference conference, String name, String email)
    // actingUser must hold ANY committee role on the conference (REVIEWER, CHAIR, CO_CHAIR --
    // reuse CommitteeService.hasRole checked against each, or ADMIN as override) since only
    // conference participants should be able to recruit on the conference's behalf.
    // Creates PersonInvitation(purpose=REVIEWER_RECRUITMENT, status=INVITED, invitedBy=actingUser),
    // sends email immediately, lastSentAt=now.
```

Every authorization check reuses `CommitteeService` exactly as established in the committee-roles feature (`docs/superpowers/specs/2026-09-19-committee-roles-design.md`) — no new authorization primitive is introduced.

### `SubmissionService.submitPaper` hook

After the existing author-saving loop, for each `PaperAuthor`: look up `userRepository.findByEmail(author.getEmail())`; if absent, call `personInvitationService.inviteCoAuthor(savedPaper, author)`. This runs inside the same `@Transactional` method — if invitation creation fails, the whole submission rolls back, which is correct (a submission with an author who can't be invited shouldn't silently half-succeed).

### `AuthService` repository fix

`AuthService` currently depends on `org.confcms.cms.auth.repository.UserRepository` (bean name `"authUserRepository"`), a functionally-identical duplicate of the canonical `org.confcms.cms.repository.UserRepository` used everywhere else. Repoint `AuthService` to the canonical repository; delete the duplicate interface. Confirmed via `grep`: `AuthService` is the only importer of the duplicate.

---

## 3. Rejected-Suggestion Follow-Up UI

A "pending invitations" screen (Chair/Co-Chair/Admin view, scoped to their conference) lists `PersonInvitation`s by status. A `REJECTED` `REVIEWER_SUGGESTION` row shows an inline "assign someone else" action that calls the *existing* `ReviewAssignmentService.assignReviewer(...)` or `autoAssignReviewers(...)` directly (both already built and authorization-checked from the committee-roles feature) — no new assignment logic is introduced here, this is purely surfacing an existing capability at the point it's needed.

---

## 4. Testing & Error Handling

**Tests:**
- `ReviewAssignmentServiceTest` additions: `declineAssignment` rejects a blank reason; rejects when neither/both suggestion forms are present; rejects when `actingUser` isn't the assignment's own reviewer; creates a `PersonInvitation` only when the suggestion is external.
- `PersonInvitationServiceTest`: `approve`/`reject` authorization (chair/admin only, `REVIEWER_SUGGESTION` only); `resend` authority for all three per-purpose paths; `resend` rate-limit enforcement (count cap and time-interval cap, tested independently); `acceptInvitation` rejects expired and already-used tokens; `CO_AUTHOR`/`REVIEWER_RECRUITMENT` invitations are created at `INVITED` directly (never `PENDING_APPROVAL`).
- `SubmissionServiceTest` addition: submitting with a co-author email that matches no existing `User` triggers `inviteCoAuthor`; an author whose email does match an existing `User` does not trigger an invitation.
- `ConflictDeclarationServiceTest` (or equivalent): declaring a conflict is idempotent per `(conference, reviewer, declaredAgainstUser)` (relies on the unique constraint, service catches and no-ops or returns the existing row rather than erroring on a re-declare).

**Error handling** (per project standard — explicit handling, user-friendly messages, no silent swallowing):
- `SecurityException` → HTTP 403 with message, consistent with every controller so far in this codebase.
- Expired/used/unknown token on `acceptInvitation` → `IllegalArgumentException` → 400 with a clear message, never a stack trace.
- Resend rate-limit exceeded → a specific, distinct exception/message ("Please wait before resending" / "Maximum resend attempts reached"), not a generic error, so the UI can explain why the action is blocked.

---

## Summary of files touched

**New:**
- `review/domain/ReviewDecline.java`
- `domain/PersonInvitation.java`, `domain/InvitationPurpose.java`, `domain/InvitationStatus.java`
- `domain/ConflictDeclaration.java`
- `repository/ReviewDeclineRepository.java`, `repository/PersonInvitationRepository.java`, `repository/ConflictDeclarationRepository.java`
- `service/PersonInvitationService.java`
- `service/ConflictDeclarationService.java` (or fold into `ReviewAssignmentService` if small enough at implementation time — left as an implementation-planning decision, not fixed here)
- Tests for all of the above

**Modified:**
- `review/service/ReviewAssignmentService.java` (`declineAssignment`)
- `submission/service/SubmissionService.java` (co-author invitation hook)
- `auth/service/AuthService.java` (repoint to canonical `UserRepository`)
- New controller(s) for: decline submission, invitation approve/reject/resend, invitation acceptance page, reviewer recruitment, conflict declaration — exact controller shape (one new controller vs. extending `ReviewRestController`) left to implementation planning.

**Deleted:**
- `auth/repository/UserRepository.java` (the duplicate)

---

## Deferred: Reviewer specialist fields (queued follow-up brainstorm)

Raised in this session: reviewers should be taggable with specialist fields (e.g. "robotics," "social science") so Chair/Co-Chair/Admin can filter reviewers by field during manual or auto-assignment, avoiding mismatched assignments. The currently-orphaned `SubTheme` entity (conference-scoped topic, exists in the schema, referenced by nothing else — confirmed via `grep` during this session) is the natural foundation, since conference themes narrowing/broadening over time is exactly what it already models structurally, just unwired. This needs its own design pass covering: does a reviewer have one field or many; do fields nest under conference-specific `SubTheme`s or exist as a reviewer's standing profile independent of any one conference; how filtering interacts with the existing bid-based auto-assignment scoring in `ReviewAssignmentService.calculateScore`. Not designed here — next brainstorm after this feature ships.
