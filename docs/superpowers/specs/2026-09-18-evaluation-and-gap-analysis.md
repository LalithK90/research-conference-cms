# Research Conference CMS — Evaluation & Gap Analysis

**Date:** 2026-09-18
**Status:** Draft for review — feeds the next implementation plan, not a plan itself.
**Scope:** Part 1 evaluates the current codebase against its own README goals and against `ai-mert.ait.ac.th` (the conference this product will replace). Part 2 maps the new special requirements onto that baseline as a gap analysis with recommendations.

**Product framing (confirmed with stakeholder):** This is being built to be sold/distributed as a **generic, reusable, self-hosted product** for any institution running a research conference — not a system with AIT/AI-MERT specifics baked in. Distribution model is **single-tenant, self-hosted**: each institution deploys and runs its own instance (matches the current README). No cross-institution data isolation or platform-level tenancy is required. The practical implication: nothing conference-specific or institution-specific should be hardcoded — it must be config/data set up per deployment, not code.

---

## Part 1 — Current State Evaluation

### 1.1 What exists today

A Spring Boot 3.5 / Java 21 monolith (Thymeleaf + MySQL/H2 + JPA) already covers a meaningful slice of the lifecycle:

need to update today version 
  id 'org.springframework.boot' version '4.1.1'
  id 'io.spring.dependency-management' version '1.1.7'

| Area | What's implemented |
|---|---|
| Conference | `Conference` entity with title/venue/dates/logo/active-flag, sub-themes, steering committee, one payment config |
| Submission | `Paper`, `PaperVersion` (revisions), `PaperAuthor`, track field, PDF upload |
| Review | `ReviewBid` (Eager/Willing/Conflict), `ReviewAssignment`, `Review` (scoring), a bidding-based assignment service |
| Decisions | `DecisionService`, admin decision screens (accept/reject) |
| Scheduling | `Room`, `Session`, `Presentation`, a `SchedulingRestController` |
| Registration | `Registration`, `PaymentStatus`, Stripe/PayPal/bank-transfer/free config per conference |
| Auth | Form login (email/password) + a working `MagicLink` entity/service/repository already wired |
| Proceedings | A `ProceedingsService` that PDF-merges accepted papers with blank placeholder cover/TOC pages |
| Public site | Home, Committee, Register, Login templates |

This is a stronger starting point than "greenfield" — most of the domain vocabulary (paper, track, bid, assignment, decision, session, registration) already matches conference-management norms and doesn't need to be reinvented.

### 1.2 Structural problem: two parallel domain models

The codebase is mid-migration from a flat package layout (`com.icosiam.cms.domain.*`, `com.icosiam.cms.repository.*`, `com.icosiam.cms.service.*`) to a feature-packaged layout (`com.icosiam.cms.auth.*`, `submission.*`, `review.*`, `scheduling.*`, `registration.*`, `proceedings.*`). Both sets of classes currently coexist:

- Two `User` classes: `com.icosiam.cms.domain.User` and `com.icosiam.cms.auth.domain.User` — the latter is a **plain POJO with no `@Entity` annotation**, not persisted via JPA in the shown form.
- Two `Paper`, two `BaseEntity`, two `Review`, two `PaperVersion`, two `PaperStatus`/`PaymentStatus`.
- **Cross-wiring bug:** `com.icosiam.cms.review.domain.ReviewAssignment` declares `@JoinColumn(name = "reviewer_id")` against `com.icosiam.cms.domain.User` (the legacy package) while `com.icosiam.cms.review.domain.ReviewBid` in the same package imports `com.icosiam.cms.domain.BaseEntity` and `com.icosiam.cms.domain.User` too — i.e., the "new" review package's JPA relationships point at the "old" domain package, not the new `auth.domain.User`. This is very likely a half-finished refactor rather than an intentional design.
- `ProceedingsService` is registered as `@Service("proceedingsServiceLegacy")` under `@Profile("!dev")` — naming and profile-gating strongly suggest a newer proceedings implementation was intended to replace it but isn't shown/finished.

**Recommendation:** Before adding any new feature, finish the migration: pick the feature-packaged layout as canonical, delete the flat legacy package, fix `ReviewAssignment`/`ReviewBid` to reference the real persisted `User` entity, and make `auth.domain.User` an actual `@Entity`. Doing new work (chair/co-chair, ORCID, etc.) on top of two conflicting `User` models will compound the bug, not just tolerate it.

### 1.3 Security gaps for a sellable product

- `SecurityConfig` disables CSRF outright (comment says "for simplicity in this demo, enable in prod"). For a product being distributed to real institutions, CSRF must be on by default — self-hosting customers won't remember to flip this.
- OAuth2 client dependency is present (`spring-boot-starter-oauth2-client`) but no registrations are wired in code; Google/Microsoft are only sketched as commented-out properties. ORCID isn't mentioned anywhere yet.
- No role exists yet for chair/co-chair — only `ADMIN`, `REVIEWER`, `AUTHOR` appear in `SecurityConfig`.
- Default seeded admin credentials (`admin@icosiam.com` / `admin`) are documented in the README in plaintext — fine for local dev, but a sellable product needs a forced first-run password change / setup wizard instead of a published default credential.

### 1.4 Comparison against `ai-mert.ait.ac.th`

The live site's structure maps closely onto what already exists or is easy to model as static/config content:

| ai-mert.ait.ac.th section | Current repo coverage |
|---|---|
| Home, About, Speakers, Venue/Accommodation/Transportation, Contact | Static-content territory — `public/home.html`, `public/committee.html` exist; the rest are template gaps, not architecture gaps |
| International/Local Organizing Committee | `SteeringCommitteeMember` entity + `committee.html` — already modeled |
| Call for Papers / Submission Guidelines | Missing as a page, trivial to add as static/config text |
| **Submission Form / paper management** | **This is the actual gap** — ai-mert.ait.ac.th links out to an external submission portal; this repo already has the submission domain (`Paper`, `PaperVersion`) but no visible end-to-end author-facing submission UI wired to it |
| Registration + deadline tracking | `Registration`/`PaymentStatus` domain exists; UI (`public/register.html`) exists |
| Proceedings / final publication | **Not real yet** — `ProceedingsService` only concatenates PDFs with blank placeholder pages; no TOC content, no cover content, no DOI/repository deposit |

**Conclusion matching your intent:** the site's *public-facing content* (About, committee, venue, contact) is exactly the kind of thing that should become boilerplate Thymeleaf templates with a handful of per-conference override fields — not new database tables for every paragraph. The genuine gap — the reason ai-mert.ait.ac.th still needs an external tool — is **submission → review → decision → proceedings**, which is also where this repo already has the most (if buggy) domain modeling. That validates focusing new work there rather than on public content pages.

### 1.5 What's genuinely per-conference (DB) vs. static (template/config)

| Per-conference (needs DB row) | Static / config (template or properties, not a DB table) |
|---|---|
| Dates, venue, tracks/sub-themes, active flag | "About this conference series" boilerplate prose |
| Committee members (chair, co-chairs, reviewers) | Navigation structure, page layout, branding CSS |
| Papers, versions, authors, reviews, bids, assignments | Legal/footer text, license/citation notices |
| Registration records, payment config values | Email *templates* (subject/body shape) — the *values* filled into them (deadlines, names) are per-conference data |
| Sessions/rooms/schedule | Generic "Call for Papers" wording (institution customizes once at setup, not per year) |

This gives a concrete rule for later implementation: if a field changes when the *conference* changes (new year, new topic), it's a DB column on `Conference` or a related entity. If it only changes when the *installation* changes (a different institution deploys their own copy), it's an `application.properties`/admin-settings value, not new schema. If it never changes across either axis, it's static template text.

---

## Part 2 — New Requirements Gap Analysis

### 2.1 Chair / co-chair roles + desk review

**Requirement:** one Conference Chair, multiple Co-Chairs; both Chair and Co-Chairs can perform desk-review (accept/reject/send-to-review before full peer review) and assign reviewers.

**Gap:** No `CHAIR`/`CO_CHAIR` role exists. `Conference` has no chair relationship at all — only an unrelated `SteeringCommitteeMember` list. `SecurityConfig` only recognizes `ADMIN`/`REVIEWER`/`AUTHOR`.

**Recommendation:**
- Add a `CommitteeRole` join concept: `Conference` ↔ `User` with a role enum (`CHAIR`, `CO_CHAIR`, `REVIEWER`, ...) and `conference_id`, so the same person can be Chair of one conference and a plain Reviewer of another (important once this is sold to multiple institutions each running their own recurring events).
- Exactly one `CHAIR` per conference is a business rule enforced in the service layer (unique constraint on `(conference_id, role='CHAIR')`), not a separate hardcoded field — keeps the model uniform for Chair/Co-Chair/Reviewer alike.
- A `DeskReviewDecision` (or reuse `DecisionService` with a `stage` field: `DESK_REVIEW` vs `FINAL_DECISION`) authorized to anyone with `CHAIR` or `CO_CHAIR` on that paper's conference — not global `ADMIN`.

### 2.2 Reviewer decline with required reason + required suggested replacement

**Requirement (confirmed):** declining a `ReviewAssignment` requires both a reason and a suggested-replacement reviewer — both mandatory.

**Gap:** `AssignmentStatus` and `ReviewAssignment` have no decline/reason/suggestion fields at all today.

**Recommendation:**
- Extend `AssignmentStatus` with `DECLINED`.
- Add to `ReviewAssignment` (or a new `ReviewDecline` child entity, cleaner for audit history): `declineReason` (text, required), and a suggested-replacement reference that can point either to an existing `User` (`suggestedReviewerId`, nullable) or an unregistered person's name+email (`suggestedReviewerName`/`suggestedReviewerEmail`, nullable) — validated so exactly one of "existing user" or "external name+email" is present.
- Validation lives at the service boundary (per your global input-validation rule): reject the decline request with a clear error if reason is blank or no replacement (existing-or-external) is supplied.

### 2.3 Chair/co-chair-approved auto-invite for unknown suggested reviewers

**Requirement (confirmed):** if the suggested reviewer isn't in the system, the chair/co-chair must approve before an invite email is auto-sent; nothing goes out unreviewed.

**Gap:** No invite-review queue exists; `EmailQueueService`/`EmailService` exist and can be reused as the send mechanism once approved.

**Recommendation:** model this as a small state machine, not a special case bolted onto decline:
1. Decline recorded → if suggested replacement is external (no matching `User`), create a `ReviewerInvitation` row: `status = PENDING_CHAIR_APPROVAL`, stores name/email/suggested-by/paper/conference.
2. Chair/Co-Chair dashboard lists pending invitations for their conference → **Approve** or **Reject**.
3. On Approve: generate a registration/magic-link-style invite token, queue an email via the existing `EmailQueueService`, set `status = INVITED`.
4. On the invitee following the link: they land on a lightweight signup (name confirmation + choose an auth method — see 2.4) that creates their `User` with `REVIEWER` role scoped to that conference, and status becomes `ACCEPTED`.
This reuses existing email infrastructure and the existing magic-link token pattern — no new email/token subsystem needed.

### 2.4 Multi-auth: password, magic link, Google OAuth2, ORCID OAuth2, Zenodo

**Current state:** password login works; magic link is implemented (`MagicLinkService`) but I didn't find it wired into a controller/security filter in what's been read — needs verification during planning. Google OAuth2 dependency is present but unconfigured. ORCID and Zenodo: no code at all.

**Key fact for planning:** ORCID and Zenodo are **both OAuth2/OIDC-compatible providers** (ORCID publishes a standard OAuth2 API; Zenodo, being CERN/EU-operated, also exposes OAuth2 for its API). Spring's `spring-boot-starter-oauth2-client` — already a dependency — supports **registering multiple arbitrary OAuth2 providers** via `spring.security.oauth2.client.registration.<name>` and `.provider.<name>`, the same way the commented-out Google/Azure blocks in `application.properties` show. This means Google, ORCID, and Zenodo don't need three different integration approaches — they're three registrations of the same Spring Security mechanism, differing only in their authorization/token/user-info URIs and how the returned profile maps to a local `User`.

**Recommendation:**
- Finish and wire the existing `MagicLink` flow into `SecurityConfig` (currently only form login is configured there).
- Add OAuth2 client registrations for Google, ORCID, and Zenodo following the same property pattern already sketched for Google/Azure. ORCID's OAuth2 endpoints and a custom `OAuth2UserService` to map ORCID iD → `User` (storing the ORCID iD on `User` as an identity field — valuable for author disambiguation in proceedings) is the main new code; Zenodo similarly, primarily useful later for the proceedings-deposit step in 2.5, and optionally as a login method as requested.
- One `User` identity model (fixing 1.2 first) must support: a nullable `passwordHash`, a nullable `orcidId`, and provider-linked identities — a `UserIdentity` table (`user_id`, `provider`, `provider_user_id`) is the standard pattern for "same person, multiple login methods" and avoids adding a new nullable column to `User` per provider.
- Since this is a distributable product, **every provider must be individually enable/disable-able per installation** (matches the existing commented-out-by-default pattern) — an institution without ORCID/Zenodo credentials configured should just not show that login button, not error.

### 2.5 Proceedings / final document processing

**Requirement:** produce the actual final published output, not a placeholder PDF merge.

**Gap (from 1.1/1.4):** `ProceedingsService` merges accepted papers with blank cover/TOC pages — no real cover content, no generated table of contents, no metadata, no DOI/repository deposit.

**Recommendation (scoped for a later planning pass, not this doc):**
- Cover page and TOC should be generated from actual `Conference`/`Paper` data (title, dates, ordered paper list with page numbers) using PDFBox's existing text-drawing APIs already in the dependency — no new library needed.
- Zenodo integration (once its OAuth2 client exists per 2.4) can double as the deposit target: after chair sign-off, push the generated proceedings PDF + metadata to Zenodo via its REST deposit API to obtain a DOI — this is likely the actual "final processing" step you mean by "make final document."
- This deserves its own focused brainstorm/spec once 2.1–2.4 are settled, since it depends on the chair-approval workflow (who signs off on "final") and on ORCID data (author identity for citation metadata) already being in place.

### 2.6 Conference cadence (yearly / 6-month / quarterly)

**Requirement:** the system should work whether an institution runs one conference a year, every 6 months, or quarterly.

**Finding:** this requires **no schema change**. `Conference` already has independent `startDate`/`endDate`/`isActive` and the README already documents "multiple conference profiles, switch Active instantly." Running a conference every quarter vs. once a year is purely a matter of how often the institution's admin creates a new `Conference` row — it's an operational/UX question (make "duplicate previous conference's settings" a one-click action when creating a new one, since committee/tracks/payment-config often carry over) rather than a data-model gap.

**Recommendation:** add a "Clone from previous conference" action when creating a new `Conference`, copying committee roles, sub-themes, and payment config as a starting point — the main real friction for frequent (quarterly) recurrence, not a structural change.

### 2.7 Package/group id must not stay `com.icosiam`

**Requirement (confirmed):** since this is meant to be sold/distributed generically, the codebase can't stay under an institution-specific name.

**Finding:** `group = "com.icosiam"` in `build.gradle` and every Java package (`com.icosiam.cms.*`) carries the origin institution's name. For a distributable product this should be a vendor-neutral identifier (e.g. `org.confcms` / whatever brand the product ships under) — the same way "conference_cms"-style generic naming already appears in the DB URL default (`brain_boost` is actually the opposite problem — an unrelated placeholder name that should also be reconsidered).

**Recommendation:** a mechanical, IDE-assisted rename (package rename across all Java files + `build.gradle` group + DB name default), done in the *same* pass as the domain-model consolidation (1.2), since both touch every file anyway — doing them separately means touching every file twice.

### 2.8 Reviewer paper access + feedback delivery to authors

**Requirement (confirmed):** reviewers must be able to view the actual paper in-browser, submit feedback through a proper template, and that feedback must reach the author(s).

**Gap (verified in code):** `ReviewRestController` exposes `/review/my` (list assignments) and `/review/submit` (score + comments + confidentialComments), but there is **no endpoint that serves the paper file to a reviewer** — a reviewer can see they have an assignment but nothing wires them to the PDF itself. `ReviewService.submitReview` persists the `Review` and marks the assignment `COMPLETED`, but nothing ever reads `comments` back out to notify the author — the two existing email templates (`submission_confirmation.txt`, `acceptance_notification.txt`) don't cover "here is reviewer feedback on your paper." `confidentialComments` is stored but nothing enforces that it's chair-only-visible versus `comments` being author-visible — that split exists only in field naming, not in access control.

**Recommendation:**

- Add a reviewer-scoped paper-view endpoint (e.g. `GET /review/assignment/{id}/paper`) that checks the requesting user actually holds that `ReviewAssignment` before streaming the file — this is also where download-audit logging (2.9) hooks in naturally, since "reviewer opens the paper" is itself a download event worth recording.
- Add a `email/review_feedback.txt` template (matching the existing pattern in `templates/email/`) and, on `submitReview` (or on chair sign-off if you want a chair gate before feedback goes out — worth deciding, see below), send it via the existing `EmailQueueService` using only `comments` (never `confidentialComments`) plus paper title/track.
- Enforce the confidential/author-visible split as an actual authorization rule, not just naming: an `AUTHOR`-facing view/endpoint must reject any attempt to read `confidentialComments`; only `CHAIR`/`CO_CHAIR`/`ADMIN` can see it.
- **Open question to decide before implementation:** should feedback email to the author go out automatically the moment a reviewer submits, or only after the chair/co-chair has seen all reviews and made a decision (bundling feedback with the decision notice)? The existing `acceptance_notification.txt` template suggests decisions are already communicated as a batch — feedback likely belongs in that same notification rather than one email per reviewer completing independently, to avoid an author getting 3 separate emails before knowing the outcome. Recommend bundling with the decision notice; flag if you want per-review-immediate instead.

### 2.9 Paper download audit log + duplicate/prior-submission detection

**Requirement (confirmed):** record every download of a research paper so that, if the same paper turns up submitted elsewhere later, there's a record. Two distinct capabilities, both wanted.

**Gap (verified in code):** neither `FileStorageService` (there are, again, **two** — `com.icosiam.cms.service.FileStorageService` and `com.icosiam.cms.core.service.FileStorageService`, the same duplicate-package problem as 1.2, now found a third time) has any download-tracking hook. There is no audit table anywhere in the schema.

**Recommendation — split into the two capabilities, since they have different designs:**

*(a) Access/download audit log* — a `PaperDownloadLog` (or generically `FileAccessLog`, since reviewers viewing papers in 2.8 is the same kind of event) entity: `paper_id` (or `paper_version_id`, to know exactly which revision), `user_id`, `downloaded_at`, `ip_address`. Written from one place — inside `FileStorageService.load()` (once consolidated to one class per 1.2/2.7) or a thin wrapper around it — so every download path (reviewer view, author re-download, admin export, proceedings generation) is captured without each caller remembering to log it themselves. This is a pure audit trail; no business logic needed beyond "write a row."

*(b) Duplicate/prior-submission detection* — a materially different problem: comparing a *newly submitted* paper against everything previously submitted (in this conference, and potentially across conferences if the same institution runs several). Cheapest useful version: hash the uploaded PDF content (e.g. SHA-256) and store it on `PaperVersion`; on new submission, check for existing rows with the same hash and flag an exact-duplicate match to the chair for review. This catches literal re-uploads cheaply. **Note:** true "resubmitted to a different journal with edits" detection (not byte-identical) needs text-similarity comparison, which is a meaningfully bigger feature (extract text, compare against a corpus) — recommend shipping the hash-based exact-match check first, and treating similarity-based detection as a separate future item rather than bundling it in now.

### 2.10 PDPA / personal-data protection — technical baseline

**Requirement (confirmed):** prioritize the concrete technical gaps now; treat consent/retention policy as a later, separate item.

**Gap (already identified in 1.3, restated here under its actual driver):** CSRF is disabled; the default admin password is published in plaintext in the README; no field-level encryption exists for sensitive personal data (payment details already go through Stripe/PayPal so card data itself isn't stored — good — but things like national ID-style fields if any institution adds them, or raw email addresses used for the new download-audit log in 2.9, are stored as plain columns).

**Recommendation (technical baseline only, per your scope choice):**

- Re-enable CSRF in `SecurityConfig` (currently explicitly disabled) — this alone is the single highest-value fix, since it's a real, exploitable gap today, not a future risk.
- Replace the published static default admin credential with a forced first-run setup flow (generate a random password on first boot, print it once to the server log or require setting one during initial DB seed, never in the README).
- Enforce TLS at the deployment/reverse-proxy layer (document this as a deployment requirement for self-hosting institutions, since the app itself can't force HTTPS if it's behind a customer's own proxy).
- Encrypt at rest any personal-data column beyond what's already delegated to Stripe/PayPal — the download-audit log's `ip_address` (2.9) is itself personal data under PDPA and should have a documented retention limit, not be kept forever by default.
- This is explicitly the *technical* baseline; consent capture, retention/deletion workflows, and breach-notification process are noted here as a real future requirement but out of scope for this pass, per your answer.

---

## Summary: recommended order of work

1. **Fix the domain-model split + package rename** (1.2, 2.7) — blocking, everything else builds on `User`/`Paper`/review entities, and doing the rename separately means touching every file twice.
2. **Committee roles** (2.1) — Chair/Co-Chair as conference-scoped roles, replacing the implicit assumption that `ADMIN` does everything.
3. **Reviewer decline + suggestion + chair-approved auto-invite** (2.2, 2.3) — the specific workflow you called out as special.
4. **Reviewer paper access + feedback-to-author** (2.8) — depends on 1.2 (needs one real `User`/file-storage model) but not on 2.1–2.3.
5. **Multi-auth** (2.4) — magic link wiring + Google/ORCID/Zenodo as OAuth2 registrations, `UserIdentity` table.
6. **Security/PDPA technical baseline** (2.10) — CSRF, default-credential fix; small and independent, can realistically be done anytime, but do it early since it's a live exposure, not a feature gap.
7. **Download audit log + duplicate detection** (2.9) — hooks into the same file-storage consolidation as step 1; the hash-based duplicate check is cheap once storage is unified.
8. **Proceedings real implementation + Zenodo deposit** (2.5) — depends on 2.1 (chair sign-off) and 2.4 (ORCID metadata), so sequenced last.
9. **Conference cloning UX** (2.6) — small, can slot in anytime, no dependencies.

Each numbered item above is sized to become its own brainstorm → spec → implementation-plan cycle, per the project's existing architectural workflow, rather than one combined plan.
