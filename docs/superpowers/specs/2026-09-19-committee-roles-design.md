# Committee Roles (Chair/Co-Chair/etc.) — Design

**Date:** 2026-09-19
**Status:** Approved, ready for implementation planning.
**Scope:** Implements evaluation-doc items 2.1 (Chair/Co-Chair desk review + reviewer assignment) and 2.11 (committee selection at conference creation). Feeds into but does not implement 2.13 (plagiarism), 2.14 (finance/payment-slip workflow), 2.3 (auto-invite), or 2.5 (real proceedings) — those remain separate future work; this design only introduces the roles those features will later check for.

---

## Context

Today authorization is global-only: Spring Security roles `ADMIN` / `REVIEWER` / `AUTHOR` via `@PreAuthorize`. There is no way to express "this person is the Chair of *this* conference" — `SteeringCommitteeMember` (free-text `name`/`role`/`bio`/`imageUrl`) exists only for the public committee page and has zero connection to permissions. Conference creation (`AdminConferenceController.saveConference`) has no committee/chair fields at all. Desk review (accepting/rejecting a paper before it's ever assigned to a reviewer) does not exist as a concept; `DecisionService.applyDecision` only handles a final decision assumed to happen after peer review.

## Goals

- A person can hold different committee roles on different conferences (Chair of one, plain Reviewer of another), and conferences can run concurrently (matches the evaluation doc's quarterly-cadence framing).
- Exactly one Chair per conference; Co-Chairs are 0+.
- Chair/Co-Chair can perform desk review and assign reviewers for their own conference; `ADMIN` retains override power for both, for support/troubleshooting and for conferences that don't have a Chair assigned yet.
- Conference creation requires selecting a Chair (from existing Users only — invite-by-email is separate future work, item 2.3).
- The public committee page renders from the same data that grants permissions — no second, disconnected source of truth.

## Non-goals (explicitly deferred)

- Multi-track-conference roles (Track Chair, Session Chair, Subreviewer) — no multi-track support exists yet to attach them to.
- Publication-production roles (Copyeditor, Layout Editor, Proofreader) — no real proceedings-generation pipeline exists yet (evaluation doc item 2.5); `PROCEEDINGS_MANAGER` is added now as the permission gate that feature will check, but the feature itself is not built here.
- Invite-by-email during conference creation (evaluation doc item 2.3) — Chair/Co-Chair selection this round is existing-Users-only.
- Display-only committee members with no `User` account — every committee member (including honorary/display-only ones) is a real `User`.

## Research basis

Role set validated against EasyChair's published Conference/Registration/Proceedings environment roles and general academic conference committee structure (Registration Chair, Finance Chair, Publications Chair), not invented from scratch. Enterprise event-platform roles (exhibitor/sponsor/booth management — Cvent, RainFocus) were checked and excluded as out of scope for an academic research conference.

---

## 1. Data Model

### `CommitteeRole` enum

New file `org.confcms.cms.domain.CommitteeRole`:

```java
public enum CommitteeRole {
    CHAIR,
    CO_CHAIR,
    REVIEWER,
    FINANCE_MANAGER,
    REGISTRATION_MANAGER,
    PROCEEDINGS_MANAGER
}
```

- `CHAIR` / `CO_CHAIR`: desk review + reviewer assignment for their conference.
- `REVIEWER`: committee-membership fact (shows on public committee page); distinct from and unrelated to `ReviewAssignment`/`ReviewBid` — does not imply any papers are assigned.
- `FINANCE_MANAGER`: payment-slip verification (gates future item 2.14; no gated action exists yet in this codebase).
- `REGISTRATION_MANAGER`: participant/attendee check-in and registration verification (no gated action exists yet).
- `PROCEEDINGS_MANAGER`: proceedings compilation/production (gates future item 2.5; no gated action exists yet).

### `ConferenceCommitteeRole` entity

New file `org.confcms.cms.domain.ConferenceCommitteeRole`:

```java
@Entity
@Table(name = "conference_committee_roles",
       uniqueConstraints = @UniqueConstraint(columnNames = {"conference_id", "user_id", "role"}))
@Getter
@Setter
public class ConferenceCommitteeRole extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommitteeRole role;

    private String displayTitle; // optional public-page label, e.g. "Publicity Chair"; null -> render role.name()

    @Column(columnDefinition = "TEXT")
    private String bio; // per-conference override

    private String photoUrl; // per-conference override
}
```

`displayTitle` carries no authorization meaning — purely cosmetic for the public committee page. A person can hold multiple roles on one conference as separate rows (e.g. `CHAIR` + `FINANCE_MANAGER`), not prevented by the schema, just not required.

### Changes to existing entities

- `Conference.steeringCommittee: List<SteeringCommitteeMember>` → `Conference.committeeRoles: List<ConferenceCommitteeRole>` (same `@OneToMany(mappedBy = "conference", cascade = CascadeType.ALL, orphanRemoval = true)` pattern).
- Delete `SteeringCommitteeMember.java` and its repository entirely — fully replaced.

---

## 2. Authorization / Service Layer

### `CommitteeService`

New file `org.confcms.cms.service.CommitteeService`, matching where `ConferenceService` and `DecisionService` already live (confirmed: `Conference` itself lives in `org.confcms.cms.domain`, not a feature package — this is the canonical location following the earlier domain-model-merge work, not the pre-merge feature-package convention):

```java
@Service
@RequiredArgsConstructor
public class CommitteeService {

    private final ConferenceCommitteeRoleRepository repository;

    boolean hasRole(User user, Conference conference, CommitteeRole role) { ... }

    boolean isChairOrCoChair(User user, Conference conference) {
        return hasRole(user, conference, CommitteeRole.CHAIR)
            || hasRole(user, conference, CommitteeRole.CO_CHAIR);
    }

    List<ConferenceCommitteeRole> getCommitteeForConference(Conference conference) { ... }

    @Transactional
    ConferenceCommitteeRole assignChair(Conference conference, User user) {
        // delete any existing CHAIR row for this conference, then insert the new one
    }

    @Transactional
    ConferenceCommitteeRole addRole(Conference conference, User user, CommitteeRole role, String displayTitle) {
        // relies on the DB unique constraint to reject duplicates; catches
        // DataIntegrityViolationException and rethrows as a clean IllegalStateException
        // with a user-facing message
    }

    @Transactional
    void removeRole(Long conferenceCommitteeRoleId) { ... }
}
```

`assignChair` auto-demotes the prior Chair in the same transaction (delete old `CHAIR` row, insert new), mirroring the existing "only one active conference" pattern already in `ConferenceService.saveConference`.

### Where checks apply

Two-layer pattern throughout: a coarse `@PreAuthorize` role check at the controller (must be *some* kind of staff user), then a precise per-conference check inside the service.

- **`DecisionService.applyDecision(User actingUser, Long paperId, String decision)`** and **`applyBulkDecision(...)`**: gain an `actingUser` parameter. At the top: reject with `SecurityException` unless `actingUser` has global `ADMIN` or `committeeService.isChairOrCoChair(actingUser, paper.getConference())`.
- **`DecisionService.deskReview(User actingUser, Long paperId, DeskDecision decision)`** (new — see Section 3): same authorization check.
- **`ReviewAssignmentService.autoAssignReviewers(...)`** and **`assignReviewer(...)`**: gain an `actingUser` parameter, same `ADMIN` or `isChairOrCoChair` check.
- **`ReviewRestController`**: `/review/assign/auto` and `/review/assign/manual` change from `@PreAuthorize("hasRole('ADMIN')")` to `@PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")` as the coarse gate; the service call underneath enforces the real per-conference rule. Controllers resolve the acting `User` the same way `ReviewRestController.submitReview` already does (via `SecurityContextHolder` + `UserRepository.findByEmail`).
- All `SecurityException`s from these checks are caught in the controller and returned as HTTP 403 with the exception's message — the same pattern already established for `submitReview`/`uploadNewVersion`/`withdrawPaper`.

---

## 3. Workflow / State Changes

### Desk review as a distinct stage

`PaperStatus` gains `DESK_REJECTED` (alongside existing `SUBMITTED, UNDER_REVIEW, ACCEPTED, REJECTED, WITHDRAWN`) — kept distinct from `REJECTED` so reporting can tell "never reached peer review" apart from "rejected after review."

New `DecisionService` method, deliberately separate from `applyDecision` (which uses an untyped `String decision` parameter — a design smell already present; desk review gets its own typed method rather than compounding that):

```java
public enum DeskDecision { SEND_TO_REVIEW, DESK_REJECT }

@Transactional
public Paper deskReview(User actingUser, Long paperId, DeskDecision decision) {
    // authorization check (Section 2)
    // SEND_TO_REVIEW -> PaperStatus.UNDER_REVIEW (eligible for ReviewAssignmentService)
    // DESK_REJECT -> PaperStatus.DESK_REJECTED, email author via existing EmailService
}
```

### Conference creation requires a Chair

`AdminConferenceController.ConferenceForm` gains:

```java
@NotNull
private Long chairUserId;
private List<Long> coChairUserIds = new ArrayList<>();
```

`saveConference`: validates `chairUserId` is present (rejected with a validation error before the conference is persisted — Bean Validation `@NotNull` plus an explicit service-layer check, not one alone). After persisting the `Conference`, calls `committeeService.assignChair(conference, chairUser)`, then `addRole(..., CO_CHAIR, ...)` for each co-chair ID. Chair/Co-Chair selectors in `conference_form.html` are populated from existing `User`s via `UserRepository` — no new lookup infrastructure.

### Public committee page

`PublicWebController`'s `/committee` route queries `committeeService.getCommitteeForConference(activeConference)` and renders each row's `user.fullName`, `displayTitle` (fallback: `role.name()`), `bio`, `photoUrl`.

---

## 4. Testing & Error Handling

**Tests** (this repo's test suite currently has one file, `ReviewTest`, from the prior domain-model-merge work — this feature is the first substantial opportunity to build it out, per this project's TDD standard):

- `CommitteeServiceTest`: `assignChair` demotes a prior chair; `addRole` rejects a duplicate `(conference, user, role)`; `isChairOrCoChair` true for `CHAIR`/`CO_CHAIR`, false for `REVIEWER`-only.
- `DecisionServiceTest`: `deskReview` throws `SecurityException` for a non-Chair/non-Admin actor; `SEND_TO_REVIEW` transitions `SUBMITTED → UNDER_REVIEW`; `DESK_REJECT` transitions to `DESK_REJECTED` and triggers an email.
- Conference-creation test (service or controller level): `saveConference` rejects when no chair is selected.

**Error handling** (per project standard — explicit handling, user-friendly messages, no silent swallowing):

- `SecurityException` → HTTP 403 with message, caught at each controller (consistent with the existing `submitReview`/`uploadNewVersion`/`withdrawPaper` pattern).
- Duplicate committee-role assignment → `DataIntegrityViolationException` translated to a clean 400 ("This person already holds that role on this conference"), not a raw SQL error.
- Missing Chair at creation → `@NotNull` validation plus an explicit service-layer guard.

---

## Summary of files touched

**New:**

- `domain/CommitteeRole.java`
- `domain/ConferenceCommitteeRole.java`
- `repository/ConferenceCommitteeRoleRepository.java`
- `service/CommitteeService.java`
- Tests: `CommitteeServiceTest`, additions to `DecisionServiceTest`, conference-creation test

**Modified:**

- `domain/Conference.java` (swap `steeringCommittee` for `committeeRoles`)
- `submission/domain/PaperStatus.java` (add `DESK_REJECTED`)
- `service/DecisionService.java` (add `actingUser` param to existing methods; add `deskReview`)
- `review/service/ReviewAssignmentService.java` (add `actingUser` param)
- `web/controller/ReviewRestController.java` (relax `@PreAuthorize`, resolve acting user, catch `SecurityException`)
- `web/controller/AdminDecisionController.java` / `AdminDecisionViewController.java` (resolve and pass acting user)
- `web/controller/AdminConferenceController.java` (Chair/Co-Chair fields, validation, `CommitteeService` calls)
- `publicweb/controller/PublicWebController.java` (`/committee` renders from `CommitteeService`)
- `templates/admin/conference_form.html` (Chair/Co-Chair selectors)
- `templates/public/committee.html` (render from new data shape)

**Deleted:**

- `domain/SteeringCommitteeMember.java` and its repository
