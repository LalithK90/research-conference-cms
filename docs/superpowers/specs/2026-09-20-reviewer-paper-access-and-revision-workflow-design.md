# Reviewer Paper Access, Blind Review, Anonymized Feedback & Revision Workflow — Design

**Date:** 2026-09-20
**Status:** Approved, ready for implementation planning.
**Scope:** Implements evaluation-doc items 2.8 (reviewer paper access + feedback-to-author) and 3.1 (double-blind anonymization), extended during this design session with a full revision-decision workflow (minor/major revision categorization, chair approval, due dates, author re-upload, chair resolution, same-reviewer re-review) specified in detail by the stakeholder.

---

## Context

Today, `ReviewRestController` lets a reviewer list their assignments (`GET /review/my`) and submit a review (`POST /review/submit`), but nothing serves the actual paper file to a reviewer — there is no file-download or paper-viewing endpoint anywhere in the codebase, for any role. `ReviewService.submitReview` saves `comments`/`confidentialComments` on a `Review` and marks the assignment `COMPLETED`, but nothing ever reads `comments` back out to notify the author. `PaperStatus` has no concept of a revision decision (`MINOR_REVISION`/`MAJOR_REVISION`) — only `SUBMITTED, UNDER_REVIEW, ACCEPTED, REJECTED, DESK_REJECTED, WITHDRAWN`. `Conference` has no blind-review setting. No scheduled/background job infrastructure exists anywhere in this codebase (confirmed: no `@Scheduled`/`@EnableScheduling`).

This design builds directly on the committee-roles feature (`CommitteeService.isChairOrCoChair`, `DecisionService.applyDecision` with its acting-user authorization) and the domain-model-merge work (`Paper.conference`, canonical package layout) already merged to `main`.

## Goals

- A reviewer can view paper metadata and download/view the actual PDF for any assignment they own.
- Blind review is a per-conference setting, chosen once at conference creation (alongside Chair selection), that controls whether the reviewer-facing paper view includes author names — not a setting that changes reviewer *assignment* logic.
- Reviewer feedback (`comments`, never `confidentialComments`) reaches the author bundled with the final decision email, anonymized as "Reviewer 1", "Reviewer 2", etc. — reviewer identity never reaches the author, structurally (excluded from the email model entirely, not just omitted from the template).
- A paper not simply accepted or rejected can be categorized `MINOR_REVISION` or `MAJOR_REVISION`, system-suggested from review scores and confirmed (or overridden) by Chair/Co-Chair/Admin, who also sets a revision due date before the author is notified.
- The author can upload a corrected version while in a revision status, before the due date.
- Chair/Co-Chair/Admin reviews the correction against reviewer comments and either accepts directly or sends it back to the *same* reviewers who requested the revision for a fresh look.
- Missing the revision due date auto-rejects the paper (checked lazily on access, no new scheduling infrastructure).

## Non-goals (explicitly deferred)

- **Conference-level milestone dates** (submission deadline, notification date, camera-ready deadline). The chair sets a revision due date freely per-paper for now; `Conference` gains no new date fields in this design. Related to evaluation-doc item 3.3 (camera-ready stage), already noted there as future work.
- **Reviewer-deadline reminders/escalation** for `ReviewAssignment.dueDate` being missed — a known, separate gap, not designed here.
- **Download audit logging** (evaluation-doc item 2.9) — the new file-download endpoint is a natural future hook point but does not implement logging itself in this design.
- **A real scheduled job for auto-rejecting overdue revisions.** The lazy on-access check is the chosen mechanism; a proactive background job is explicitly deferred (confirmed: this codebase has no scheduling infrastructure today, and introducing it is a bigger decision than this feature warrants).

---

## Part A: Reviewer Paper Access, Blind Review, Anonymized Feedback

### 1. Data model

`Conference` gains one field, set via the existing conference-creation form alongside Chair/Co-Chair selection:

```java
@Column(nullable = false)
private boolean blindReview = false;
```

New response DTO (not an entity) for the reviewer paper-metadata endpoint:

```java
public record PaperReviewView(
    Long paperId, String title, String abstractText, String track,
    Integer latestVersionNumber, LocalDateTime dueDate,
    List<String> authorNames // empty (never null) when conference.isBlindReview()
) {}
```

`dueDate` comes from the `ReviewAssignment` (already exists, set at assignment time, currently never surfaced anywhere in the API) — not from the `Paper`.

### 2. Endpoints and authorization

New endpoints on `ReviewRestController`, reusing its existing `actingUser()` helper:

```java
@GetMapping("/assignment/{assignmentId}/paper")
@PreAuthorize("hasRole('REVIEWER')")
// returns PaperReviewView

@GetMapping("/assignment/{assignmentId}/paper/file")
@PreAuthorize("hasRole('REVIEWER')")
// streams the latest PaperVersion's file, Content-Disposition: inline
```

Both delegate to a new `ReviewService.getPaperForAssignment(User actingUser, Long assignmentId)`:
- Loads the `ReviewAssignment`; throws `SecurityException` unless `assignment.getReviewer().getId().equals(actingUser.getId())` — same ownership pattern already used by `submitReview` and `ReviewAssignmentService.declineAssignment`.
- No restriction based on assignment status (`PENDING` or `COMPLETED` both allowed) — matches standard practice (EasyChair/CMT let an assigned reviewer see the paper regardless of bid/completion state).
- Returns the `Paper`; the metadata endpoint maps it to `PaperReviewView` (author names populated only if `!paper.getConference().isBlindReview()`); the file endpoint resolves the latest `PaperVersion.filePath` via `FileStorageService.load(...)` and streams it with `Content-Disposition: inline` (opens in-browser rather than forcing a download dialog).

**Blind-review limitation, explicit:** the system can only control metadata and rendered fields (author-list JSON, this DTO). It cannot scrub identifying text baked into the PDF's own header/footer/content — that remains the author's responsibility, the same limitation every real conference tool has.

**Error handling:** unknown `assignmentId` → 404 with a clear message; non-owning caller → 403 (`SecurityException`); a `Paper` with no `PaperVersion` yet (shouldn't happen given `SubmissionService.submitPaper` always creates one, but checked defensively) → 404, never a `NullPointerException`.

### 3. Anonymized feedback bundled with the decision email

`DecisionService.applyDecision` (already modified in the committee-roles feature to take an acting user), on `ACCEPT`/`REJECT`, additionally:
- Fetches `reviewRepository.findByPaperId(paper.getId())`.
- Builds an anonymized, numbered list for the email model: `[{index: 1, score, comments}, {index: 2, score, comments}, ...]` — **`Review.reviewer` is never placed in this model at all**, not filtered out downstream. This is a structural exclusion (the model object literally has no field capable of holding a reviewer identity), not a template-level omission, matching the same "don't even expose it" pattern used for the token-omitting DTO in the previous feature.
- `confidentialComments` is never included, for the same reason.
- Extends `acceptance_notification.txt` to render the numbered feedback list; adds a new `rejection_notification.txt` template (REJECT currently only sends a bare `sendSimpleEmail` string with no template — this design gives it the same templated treatment as acceptance).

**Confirmed not a bug (checked during design, correcting an earlier mistaken claim in this session):** `AdminDecisionViewController.paperDetail`, which renders `confidentialComments` in its Thymeleaf template, is already gated to `ADMIN` or `CommitteeService.isChairOrCoChair` only (fixed by an earlier IDOR patch this session, before this design began) — Chair and Co-Chair both pass that check, since `isChairOrCoChair` tests both roles. No further change needed there.

---

## Part B: Revision Decision Workflow

### 4. Data model

`PaperStatus` gains two values:

```java
public enum PaperStatus {
    SUBMITTED, UNDER_REVIEW, ACCEPTED, REJECTED, DESK_REJECTED, WITHDRAWN,
    MINOR_REVISION, MAJOR_REVISION
}
```

`Paper` gains:

```java
private LocalDate revisionDueDate; // nullable; set when status becomes MINOR_REVISION/MAJOR_REVISION, cleared on resolution
```

New enum for the chair's resolution action:

```java
public enum RevisionResolution { ACCEPT_DIRECTLY, SEND_BACK_TO_REVIEWERS }
```

### 5. Workflow steps and service methods

**Step 1 — system suggests, chair confirms/sets due date, author notified:**

`DecisionService.DecisionSuggestion` (existing, score-average-based) is extended to suggest `MINOR_REVISION`/`MAJOR_REVISION` bands in addition to the current ACCEPT/BORDERLINE/REJECT suggestion — exact score-band thresholds are an implementation-planning detail, not fixed here, since they're a numeric tuning choice rather than a structural design decision.

```java
@Transactional
public Paper requestRevision(User actingUser, Long paperId, PaperStatus revisionType, LocalDate dueDate)
```
- `revisionType` must be `MINOR_REVISION` or `MAJOR_REVISION` (else `IllegalArgumentException`) — the chair confirms or overrides the system's suggestion by passing whichever value they choose; there is no separate "suggestion record" entity, since the suggestion is ephemeral (computed on request, like `suggestDecisions` already is) and the chair's call to this method is the actual decision.
- Requires `ADMIN` or `isChairOrCoChair` (same `requireChairOrAdmin` pattern already in `DecisionService`).
- Sets `Paper.status = revisionType`, `Paper.revisionDueDate = dueDate`.
- Sends the author a notification email: status (minor/major revision requested), the due date, and the same anonymized bundled feedback from Part A, Section 3 (reused, not reimplemented — one shared feedback-building method).

**Step 2 — author uploads the corrected version:**

In `SubmissionService` (not `DecisionService` — this is a submission-side action by the author, matching where `uploadNewVersion`/`withdrawPaper` already live):

```java
@Transactional
public Paper uploadRevision(User actingUser, Long paperId, MultipartFile file)
```
- Ownership check: `actingUser` must be `paper.getSubmitter()` (or `ADMIN`), same pattern as `uploadNewVersion`.
- **Lazy deadline check happens first:** if `paper.getStatus()` is `MINOR_REVISION`/`MAJOR_REVISION` and `revisionDueDate` has passed, transition `Paper.status = REJECTED` immediately and reject the upload with a clear "the revision deadline has passed" message — this is the concrete implementation of the lazy auto-reject mechanism (Section 7).
- If status isn't `MINOR_REVISION`/`MAJOR_REVISION` at all → `IllegalStateException` (nothing to revise).
- On success: creates a new `PaperVersion` via the existing versioning mechanism (same pattern as `uploadNewVersion`). Status does **not** change yet — the chair still has to review it (Step 3).

**Step 3 — chair resolves the revision:**

```java
@Transactional
public Paper resolveRevision(User actingUser, Long paperId, RevisionResolution resolution)
```
- Requires `ADMIN` or `isChairOrCoChair`.
- Only valid when `Paper.status` is currently `MINOR_REVISION`/`MAJOR_REVISION` and a revision `PaperVersion` has been uploaded (else `IllegalStateException`).
- `ACCEPT_DIRECTLY`: `Paper.status = ACCEPTED`, `revisionDueDate` cleared, standard acceptance email sent (Part A's flow) — the chair has full discretion to accept without another review round, consistent with the chair's existing final-decision authority everywhere else in this app.
- `SEND_BACK_TO_REVIEWERS`: `Paper.status = UNDER_REVIEW`, `revisionDueDate` cleared. The **same** `ReviewAssignment` rows that were tied to reviewers who reviewed the pre-revision version are reset to `AssignmentStatus.PENDING`, so those specific reviewers (not a fresh auto-assignment) can submit a new `Review` against the newly-uploaded `PaperVersion`. No new `ReviewAssignmentService` method is needed for the reset itself — a repository update on the existing rows.

### 6. Reused feedback-anonymization

`requestRevision`'s author notification reuses the exact anonymized-feedback-building logic from Part A, Section 3 (extracted into one shared private method inside `DecisionService`, called by both `applyDecision` and `requestRevision`) — same numbered, reviewer-identity-free model, same exclusion of `confidentialComments`.

### 7. Lazy auto-reject mechanism, precisely

There is no background job. The single enforcement point is the deadline check inside `SubmissionService.uploadRevision` (Section 5, Step 2) — this is deliberately the only place it's checked, since it's the one action an author takes that's time-sensitive; a chair viewing the paper via `AdminDecisionViewController.paperDetail` does not need its own separate deadline check for this version, since nothing about *viewing* a stale revision is incorrect (it will show `MINOR_REVISION`/`MAJOR_REVISION` with a past due date until someone next attempts an upload or explicitly resolves it) — noted as an accepted limitation of the lazy-check approach, not a gap, per the confirmed decision to avoid new scheduling infrastructure.

---

## Part C: Testing & Error Handling

**Tests:**
- `ReviewServiceTest` (new): `getPaperForAssignment` rejects a non-owning reviewer; `authorNames` empty when `blindReview=true`, populated when `false`; `dueDate` present in the response.
- `DecisionServiceTest` additions: accept/reject email model's feedback list contains only `{index, score, comments}` — asserted by checking the model map has no key or value derived from `Review.reviewer` or `confidentialComments`; `requestRevision` rejects non-chair/non-admin, rejects a `revisionType` outside `{MINOR_REVISION, MAJOR_REVISION}`, sets `revisionDueDate` and sends the notification; `resolveRevision` with `ACCEPT_DIRECTLY` sets `ACCEPTED`; with `SEND_BACK_TO_REVIEWERS` resets the original assignments to `PENDING` and sets `UNDER_REVIEW`.
- `SubmissionServiceTest` additions: `uploadRevision` rejects when status isn't a revision status; auto-transitions to `REJECTED` and rejects the upload when the due date has passed; creates a new `PaperVersion` without changing status on success.

**Error handling** (per project standard — explicit handling, user-friendly messages, no silent swallowing):
- `SecurityException` → 403, consistent with every controller in this codebase so far.
- `IllegalStateException` (wrong status for the requested action, e.g. resolving a revision with no uploaded correction) → mapped to 409 by the controller, matching how the committee-roles feature's `resend` rate-limit used 429 for a similar "not currently valid" case — an implementation-planning detail to confirm against whatever convention the controller layer settles on, not fixed rigidly here.
- `IllegalArgumentException` (bad input, e.g. invalid `revisionType`) → 400.

---

## Summary of files touched

**New:**
- `submission/dto/PaperReviewView.java` (or `review/dto/`, exact package left to implementation planning — follows `submission/dto/AuthorRequestDto.java`'s existing pattern either way)
- `review/domain/RevisionResolution.java`
- Tests: `ReviewServiceTest`, additions to `DecisionServiceTest` and `SubmissionServiceTest`
- `templates/email/rejection_notification.txt`

**Modified:**
- `domain/Conference.java` (add `blindReview`)
- `submission/domain/PaperStatus.java` (add `MINOR_REVISION`, `MAJOR_REVISION`)
- `submission/domain/Paper.java` (add `revisionDueDate`)
- `review/service/ReviewService.java` (add `getPaperForAssignment`)
- `web/controller/ReviewRestController.java` (add the two paper-access endpoints)
- `service/DecisionService.java` (anonymized feedback in `applyDecision`; add `requestRevision`; extend `DecisionSuggestion` with revision bands)
- `submission/service/SubmissionService.java` (add `uploadRevision`)
- Wherever `resolveRevision` lives — likely `DecisionService`, alongside `requestRevision`, since both are chair-authorized paper-lifecycle actions
- `templates/email/acceptance_notification.txt` (render the numbered feedback list)
- `web/controller/AdminConferenceController.java` / `templates/admin/conference_form.html` (add the `blindReview` checkbox alongside the existing Chair/Co-Chair fields)

**Not modified (confirmed correct, no change needed):**
- `web/controller/AdminDecisionViewController.java` — already properly authorized; the `confidentialComments`-leak concern raised earlier in this design session was re-checked and found to be already fixed by an earlier patch this session.

---

## Deferred (queued for future brainstorms)

- Conference-level milestone dates (submission/notification/camera-ready deadlines) and constraining revision due dates against them.
- Reviewer-deadline reminders/escalation for missed `ReviewAssignment.dueDate`.
- Download audit logging (evaluation-doc item 2.9).
- A real scheduled background job for proactive (not lazy) auto-rejection of overdue revisions.
- Reviewer specialist-field tagging and assignment filtering (carried over from the previous feature's deferred list, still not designed).
- HTML email templates (raised mid-design; requires converting `EmailService`/`EmailQueueService` from `SimpleMailMessage` to `MimeMessage`, a cross-cutting change affecting every existing email this app sends, not just this feature's new templates — confirmed both classes hard-code plain text today).
- Caching strategy (e.g. Ehcache) — raised mid-design, entirely unscoped; needs its own investigation into what's actually hot/slow before picking an approach.
- SEO strategy for the public-facing pages — raised mid-design, unrelated to the authenticated reviewer/chair flows this spec covers; needs its own investigation into which public pages/metadata matter.
