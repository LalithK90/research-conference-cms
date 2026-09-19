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
- `ProceedingsService` is registered as `@Service("proceedingsServiceLegacy")` under `@Profile("!dev")` — naming and profile-gating strongly suggest a newer proceedings implementation was intended to replace it but isn't shown/finished. **Correction from full-codebase mapping done at plan time:** `com.icosiam.cms.proceedings.service.ProceedingsService` turned out to be a byte-identical copy of the legacy one, not a further-along replacement — both are the same placeholder PDF-merge implementation, just duplicated. This doesn't change the finding (2.5 still applies) but corrects the "newer implementation" framing.
- Two competing `@SpringBootApplication` entry points also exist — `com.icosiam.cms.ConferenceCmsApplication` and `com.icosiam.cms.web.CmsApplication` — both declaring `@EntityScan`/`@ComponentScan`/`@EnableJpaRepositories` over `com.icosiam.cms`. They only coexist without immediately breaking because `application-dev.properties` sets `spring.main.allow-bean-definition-overriding=true`, papering over duplicate bean definitions. This must be resolved as part of the same cleanup — one entry point, not two.

**Recommendation (revised after full-codebase dependency mapping at plan time — see `docs/superpowers/plans/` for the mapping):** the assumption above — "pick the feature-packaged layout as canonical" — turned out to be backwards for several classes once every import in the codebase was traced. For `User`, `Role`, `Conference`, `UserRepository`, `ConferenceService`, `EmailService`, `DecisionService`, `FileStorageService`, and `CustomUserDetailsService`, the **flat legacy package is the live, actually-wired implementation** used throughout every controller and service, while the feature-packaged look-alikes (`auth.domain.User`, `auth.repository.UserRepository`, `auth.service.CustomUserDetailsService`) are incomplete, unreferenced stubs — `auth.domain.User` isn't even a real JPA `@Entity`. Confirmed direction: **keep whichever side of each duplicate pair is actually wired in and working, class by class, regardless of which package it currently sits in** — do not delete working code just to honor the feature-package direction — then reorganize the survivors into the feature-package folder structure as a separate, purely mechanical move-only step once the "which implementation wins" decisions are all made. `ConferenceCmsApplication` (has `@EnableJpaAuditing` and an exclusion-filter pattern already anticipating this cleanup) is kept as the single entry point; `web.CmsApplication` is deleted.

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

*(b) Duplicate/prior-submission detection* — a materially different problem: comparing a *newly submitted* paper against everything previously submitted (in this conference, and potentially across conferences if the same institution runs several). Cheapest useful version: hash the uploaded PDF content (e.g. SHA-256) and store it on `PaperVersion`; on new submission, check for existing rows with the same hash and flag an exact-duplicate match to the chair for review. This catches literal byte-identical re-uploads cheaply and is worth keeping regardless of 2.13, since it's near-free and catches a case text-similarity might miss (identical file, different metadata). **Update:** true text-similarity detection (not byte-identical) is no longer out of scope — see 2.13, which now covers both internal-corpus and external-provider similarity checking as part of desk review.

### 2.10 PDPA / personal-data protection — technical baseline

**Requirement (confirmed):** prioritize the concrete technical gaps now; treat consent/retention policy as a later, separate item.

**Gap (already identified in 1.3, restated here under its actual driver, plus one newly-verified finding):** CSRF is disabled; the default admin password is published in plaintext in the README; no field-level encryption exists for sensitive personal data. **Newly verified:** `AdminConferenceController.ConferenceForm` and `ConferencePaymentConfig` store Stripe **secret key** and PayPal **client secret** as plain `String` fields in the database — confirmed by the stakeholder to currently be placeholder/dummy data, with the real design intent being to keep payment credentials out of the database entirely (see 2.14).

**Recommendation (technical baseline only, per your scope choice):**

- Re-enable CSRF in `SecurityConfig` (currently explicitly disabled) — this alone is the single highest-value fix, since it's a real, exploitable gap today, not a future risk.
- Replace the published static default admin credential with a forced first-run setup flow (generate a random password on first boot, print it once to the server log or require setting one during initial DB seed, never in the README).
- Enforce TLS at the deployment/reverse-proxy layer (document this as a deployment requirement for self-hosting institutions, since the app itself can't force HTTPS if it's behind a customer's own proxy).
- Encrypt at rest any personal-data column that must remain in the database — the download-audit log's `ip_address` (2.9) is itself personal data under PDPA and should have a documented retention limit, not be kept forever by default.
- Payment provider credentials specifically move to externalized config rather than being encrypted-in-DB — see 2.14 for the confirmed design.
- This is explicitly the *technical* baseline; consent capture, retention/deletion workflows, and breach-notification process are noted here as a real future requirement but out of scope for this pass, per your answer.

### 2.11 Committee member selection at conference-creation time

**Requirement (confirmed):** when a conference is created, the creator must select/assign the Chair (required) and Co-Chairs (0+) as part of that same creation step — a conference cannot be saved without a Chair.

**Gap (verified in code):** `AdminConferenceController.newConferenceForm`/`saveConference` and `conference_form.html` handle only title/venue/dates/logo/contact/payment config — there is **no committee or chair field anywhere in the creation flow**. `SteeringCommitteeMember` is a separate entity (free-text `role: String`, e.g. `"Conference Chair"` as a label, not an enforced role) with no visible creation-time UI wiring at all — it's unclear from what's been read whether it's populated through any screen today. This means the "select chair, then show on website" flow you're describing doesn't exist yet in either the security-role sense (2.1) or the public-committee-page sense.

**Recommendation:**

- Merge this with 2.1 rather than treating it as separate: the conference-creation form gains a required "Chair" selector (search-existing-user-or-invite-by-email, reusing the same invite mechanism from 2.3) and a repeatable "Co-Chair" add-row, writing directly into the `Conference`↔`User` role-join table from 2.1 at save time. `saveConference` must reject the submission server-side if no Chair is set — enforced in the service layer, not just a required HTML attribute, since the public API (`AdminConferenceController`, or any future REST equivalent) must not bypass it.
- `SteeringCommitteeMember` (name/role-label/bio/imageUrl) should become the **public-display projection** of the same committee-role data, not a second source of truth maintained separately — i.e., the committee-role join table is what's authoritative for permissions, and the public committee page renders from it (pulling bio/photo from the linked `User` profile, extended with conference-specific bio/photo override fields since a person's bio may differ per conference). This directly answers your "keep as dynamic" question: **committee membership, role, and per-conference bio/photo are dynamic (DB, keyed to the conference)**; a person's base profile (name, ORCID, default photo) is dynamic too but keyed to the user, not the conference — reused across every conference they're ever part of, not re-entered each time.

### 2.12 Login location tracking (GeoLite2)

**Requirement (confirmed):** wire up the currently-unused `GeoLite2-City.mmdb` file to log an approximate location per login, as a real feature (security/suspicious-login awareness), not remove it.

**Gap (verified):** `src/main/resources/static/GeoLite2-City.mmdb` exists but **no Java code references it** — no MaxMind reader, no import anywhere in `src/main/java`. It's a dropped-in asset with nothing wired to it. There's also no `com.maxmind.geoip2` dependency in `build.gradle`.

**Recommendation:**

- Add the `com.maxmind.geoip2:geoip2` dependency to `build.gradle` and move the `.mmdb` file out of `static/` (a public web-servable directory — anyone could currently download the raw geolocation database, which is at minimum a wasted-bandwidth issue and arguably a licensing concern for MaxMind's redistribution terms) into a non-served resource path (e.g. `src/main/resources/geoip/`), loaded via classpath at startup.
- On successful login (and optionally failed attempts, useful for suspicious-activity detection), resolve the request IP to city/country via `DatabaseReader.city(ip)` and record it — this is naturally the same kind of audit event as the download log in 2.9, so consider one shared `AuthEventLog`/`AccessLog` table (login events and paper-download events both being "who, when, from where, what") rather than two separate logging mechanisms.
- The resolved location is itself personal data under PDPA (2.10) — it needs the same retention-limit treatment as the download log's IP address, and MaxMind's own license requires periodic database updates (the bundled `.mmdb` will go stale) — note this as an operational requirement for self-hosting institutions, not a one-time setup step.
- **Also found, not yet in this doc:** Trumbowyg (rich-text editor) is fully vendored under `static/dist/trumbowyg*` with ready-made Thymeleaf fragments (`templates/fragments/trumbowygStyle.html`, `trumbowygScript.html`) but, like GeoLite2, **no template currently includes those fragments** — it's available but unused. Given 2.11's committee bios and the "About this conference" static content (1.5) are exactly the kind of free-text content an admin would want to format, wiring Trumbowyg into those admin-editing forms (committee bio, conference description) is low-effort since the fragments already exist — flagging it as a small win to bundle with 2.11 rather than its own item.

### 2.13 Plagiarism / similarity checking at desk review

**Requirement (confirmed):** desk review (2.1) needs similarity checking against **external sources** (the industry-standard meaning of "plagiarism check" — comparing against published literature, the web, and other institutions' submissions, not just this system's own data); checking against **this system's own database** of past submissions is a good addition on top, not a replacement.

**Gap:** No plagiarism/similarity checking exists in any form today — this is new scope, not a fix to something partially built.

**Recommendation:** since this product is self-hosted per-institution (confirmed framing throughout this doc) and different institutions already hold subscriptions to different plagiarism services (Turnitin, iThenticate, Copyleaks, PlagScan, Grammarly's checker, etc., or none at all), design this the same way payment providers already are — a **pluggable provider interface**, confirmed as the preferred approach:

- Define one interface, e.g. `PlagiarismCheckProvider { CheckResult check(PaperVersion version) }`, returning a similarity score and a report URL/reference at minimum.
- Each concrete provider (Turnitin, iThenticate, Copyleaks, ...) implements it against that vendor's actual API; which one is active is an installation-level config choice (credentials in the same externalized-properties location as payment providers — see 2.14 — since these are also third-party API credentials, not conference data).
- An installation with no plagiarism-provider credentials configured simply has the feature unavailable in the desk-review UI (same "gracefully absent, not erroring" pattern already recommended for OAuth2 providers in 2.4) — this keeps the product usable for institutions with no subscription yet.
- **Internal-database similarity check** (your "also so good" addition) is a separate, always-available check requiring no third-party subscription: compare a new submission's extracted text against the text of all previously-stored papers in this installation (which, notably, grows more useful over time and across every conference the same installation has ever run — another reason the single-tenant/self-hosted model from earlier in this doc matters, since each institution's own historical corpus stays with their own instance). A basic approach (e.g. shingling + Jaccard similarity, or an existing Java text-similarity library) is enough for a first version; this can ship independently of and before any external-provider integration, since it needs no external account.
- Both checks' results surface together on the desk-review screen the chair/co-chair (2.1) uses — external score/report link (if a provider is configured) alongside internal-corpus matches (if any) — as input to the desk-review accept/reject/send-to-review decision, not as an automatic reject.

### 2.14 Payment credentials externalized + manual bank-transfer with slip upload

**Requirement (confirmed):** all payment-provider credentials (Stripe, PayPal, etc.) move to a separate `.properties` file rather than the database (current DB fields are confirmed placeholder/dummy data, not real design intent). Additionally, manual bank-transfer payment needs a real workflow: the author uploads a payment slip through the app, and a backend/finance team member reviews it and updates the payment status.

**Gap (verified in code):** `ConferencePaymentConfig` (still living in the legacy flat package — another instance of the 1.2 duplication) stores `stripeSecretKey`/`paypalClientSecret` as DB columns with no externalization, and `Registration.paymentStatus` only has `PENDING/PAID/FAILED/REFUNDED` — no status for "slip submitted, awaiting verification," and no slip-file field exists anywhere on `Registration` (only an unrelated `invoicePath`, which reads as an outbound generated invoice, not an inbound uploaded proof-of-payment).

**Recommendation:**

- Move provider API credentials (Stripe/PayPal keys) into `application*.properties` (or environment variables, following the pattern already used for DB/SMTP credentials in this same file) — read via `@Value`/`@ConfigurationProperties`, never stored in `ConferencePaymentConfig`. `ConferencePaymentConfig` keeps only non-secret, conference-specific data (which provider is active, bank account *display* details for instructions — those aren't credentials, just information shown to payers).
- Add `AWAITING_VERIFICATION` to `PaymentStatus`, and a `paymentSlipPath` field on `Registration` (via the existing `FileStorageService`, once consolidated per 1.2) — populated when an author uploads their bank-transfer slip.
- New endpoint for authors: upload slip → `Registration.paymentStatus = AWAITING_VERIFICATION`.
- New finance-team-facing screen/role: list registrations `AWAITING_VERIFICATION` with their uploaded slip viewable, and an action to mark `PAID` or reject back to `PENDING` with a reason. This is a distinct responsibility from Chair/Co-Chair (2.1) and from `ADMIN` — worth a `FINANCE`/`REGISTRATION_MANAGER` role rather than overloading `ADMIN`, so an institution can delegate payment verification without granting full admin rights (consistent with the conference-scoped-role pattern already established in 2.1).
- This slip file is another case the download-audit log (2.9) should cover — the finance reviewer opening a slip is a download/access event like any other.

### 2.15 Author-facing full submission status (current + all previous submissions)

**Requirement (confirmed):** authors need to see full status for their submissions — both the current one and previous ones — covering everything: review/decision stage and payment/registration status together.

**Gap:** No author-facing status dashboard exists in what's been read — `SubmissionRestController` (submission endpoints) and `RegistrationController` exist independently, but nothing surfaces "here is everything about my papers and my registration, across every conference I've submitted to on this installation" in one place.

**Recommendation:**

- One author-facing "My Submissions" view, scoped to the logged-in `User`, listing every `Paper` they're an author on (via `PaperAuthor`) across **all** conferences this installation has run — not just the currently-active one — each showing: current status (`SUBMITTED`/`UNDER_REVIEW`/`ACCEPTED`/`REJECTED`, extended with a `DESK_REJECTED` stage per 2.1 if desk review ends a paper early), which version is latest, and (once 2.8 ships) a link to any reviewer feedback released to them.
- Alongside it, the author's own registration/payment status (2.14) — including `AWAITING_VERIFICATION` if they've uploaded a slip and are waiting on finance review — so "did my payment go through" and "what happened to my paper" are visible in the same place rather than two disconnected screens.
- This is a read-only aggregation view over data that will already exist once 2.1/2.2/2.8/2.14 are built — no new domain modeling required beyond exposing what's already there, but it should be designed alongside those items (not bolted on after) since it's the thing that determines what fields/statuses those items need to expose in the first place.

---

## Part 3 — Gaps found via external research (industry-standard practice)

Requested check: compare this design against how established conference-management tools (EasyChair, Microsoft CMT, HotCRP, OpenReview) actually work, to surface anything missing that wasn't raised by either the AIT comparison or the stakeholder's own requirements. Four real gaps surfaced, all standard practice in every tool checked — none of these were previously in this doc.

### 3.1 Double-blind review anonymization

**Finding:** every serious conference tool (CMT, EasyChair, OpenReview, HotCRP) treats author-identity-hiding-from-reviewers as core, not optional — because reviewer bias by author identity is a well-documented, studied problem (see PNAS research on single- vs double-blind review bias). Standard practice: the submission system automatically strips identifying metadata from what a reviewer sees, and authors are instructed to avoid self-identifying language in the paper body itself.

**Gap (verified against this codebase):** `Paper` has `submitter` and `authors` directly on the entity with no separation between "what the chair/admin sees" and "what an assigned reviewer sees." Once 2.8 (reviewer paper access) is built, a reviewer fetching the paper file and metadata would see author names/affiliations directly — there is currently no mechanism to prevent this.

**Recommendation:**

- Make blind-review mode a **per-conference setting** (not hardcoded), since some institutions/tracks run single-blind or open review instead — this fits the existing `Conference`-scoped-config pattern already used for payment/tracks.
- When enabled, the reviewer-facing paper view/download (2.8) must serve a version with author metadata stripped from what's rendered to the reviewer (author list, any header/footer identifying info in the file itself is an author responsibility per standard practice, same as every real tool — the system can't scrub arbitrary PDF body text, only what it controls: the metadata and any author-list fields it renders).
- This has to be designed as part of 2.8 (reviewer paper access), not bolted on after — it changes what that endpoint is allowed to return.

### 3.2 Conflict-of-interest declaration by name/affiliation, without de-blinding

**Finding:** standard practice (confirmed across CMT/EasyChair-style tools) separates two different CoI mechanisms: (a) authors declare CoIs with committee/reviewer members at submission time, and (b) reviewers declare CoIs against a list of **author names and affiliations only** (no paper titles/abstracts/IDs) *before* being shown any paper — this lets CoI be resolved without ever de-blinding a paper to find out if a conflict exists.

**Gap:** `ReviewBid.conflictReason` (a free-text field, populated only when a reviewer bids `CONFLICT` on a specific paper) is the only CoI mechanism that exists today. This requires the reviewer to already be looking at (or bidding on) a specific paper to discover a conflict — the opposite of the standard "declare against a name list first" pattern, and in tension with 3.1's blinding goal, since bidding-per-paper implicitally exposes something about the paper to the reviewer before any conflict is screened.

**Recommendation:** add a conference-level "declare conflicts" step for reviewers/chairs — a simple multi-select against the list of author names+affiliations for that conference's submitted papers (no titles/abstracts shown) — run once per reviewer per conference (or per assignment round), whose output feeds automatic exclusion from `ReviewAssignment`/auto-assignment (2.1's assignment logic) for any paper sharing a declared conflicted name/affiliation. This is a smaller, more standard-aligned addition on top of the existing bid/assignment system, not a replacement for it.

### 3.3 Camera-ready as its own workflow stage, with copyright-transfer collection

**Finding:** in every tool checked, "camera-ready" is explicitly a **separate stage** from the original submission, triggered only after acceptance — with materially different requirements: strict template/formatting compliance (exact conference template, embedded fonts, no page numbers since the publisher adds them), often a PDF-validation step (e.g. IEEE PDF eXpress checks the file is "Xplore-compatible" before accepting it), and a **copyright transfer form** collected alongside the file, separate from the paper itself, which is what legally grants the publisher/institution the right to include the work in the proceedings and index it externally.

**Gap:** this repo's `PaperStatus`/`PaperVersion` model has no camera-ready concept at all — accepted papers apparently flow straight into `ProceedingsService` (2.5) using whatever version was last uploaded during review, with no distinct "final formatted version + signed rights form" step in between.

**Recommendation:**

- Add a `CAMERA_READY_SUBMITTED` stage distinct from `ACCEPTED`: after a chair marks a paper `ACCEPTED`, the author gets a new upload action (reusing the existing `PaperVersion` versioning mechanism, tagged as the camera-ready version rather than a review revision) plus a required copyright-transfer acknowledgment (a simple "I agree" checkbox/timestamp is enough for a first version — full e-signature workflows are a bigger feature institutions can layer on if they need one, since requirements vary by publisher/institution).
- `ProceedingsService` (2.5) should pull specifically the camera-ready version, not just "latest version," which also cleanly separates "still being revised during review" from "final, ready to publish" — directly relevant to 2.5 since generating real proceedings depends on knowing which version is actually final.
- Institution-configurable formatting requirements (template name, page-limit, etc.) shown to authors at this stage is a nice-to-have, not required for a first version — the strict template-conformance *checking* IEEE PDF eXpress does is a genuinely large feature (PDF/A validation, font-embedding checks) worth explicitly deferring rather than attempting inline.

### 3.4 Confirmed differentiator: this product's submission+review+registration combo is not the norm

**Finding worth keeping, not a gap:** Microsoft CMT, the tool most similar in review-workflow sophistication to what's being built here, explicitly has **no registration, no payments, and no event website** — organizers running CMT still need a separate tool for those. EasyChair and others are similarly narrow. This validates a decision already implicit in this project (and confirmed earlier in this doc): building submission + review + registration/payment + public site + proceedings as one coherent product is a genuine gap in the market, not redundant effort — worth stating explicitly since it's easy to assume "surely someone already built this" when researching competitors, and the research says otherwise.

---

## Summary: recommended order of work

1. **Fix the domain-model split + package rename** (1.2, 2.7) — blocking, everything else builds on `User`/`Paper`/review entities, and doing the rename separately means touching every file twice.
2. **Committee roles + chair-required-at-creation** (2.1, 2.11) — Chair/Co-Chair as conference-scoped roles selected/invited during conference creation, replacing both the implicit "`ADMIN` does everything" assumption and the disconnected `SteeringCommitteeMember` free-text role.
3. **Reviewer decline + suggestion + chair-approved auto-invite, + CoI-by-name declaration** (2.2, 2.3, 3.2) — the specific workflow you called out as special, extended with the standard-practice CoI-before-de-blinding step found in research.
4. **Reviewer paper access + feedback-to-author + double-blind anonymization** (2.8, 3.1) — depends on 1.2 (needs one real `User`/file-storage model) but not on 2.1–2.3; blinding must be designed into this endpoint from the start, not retrofitted.
5. **Multi-auth** (2.4) — magic link wiring + Google/ORCID/Zenodo as OAuth2 registrations, `UserIdentity` table.
6. **Security/PDPA technical baseline** (2.10) — CSRF, default-credential fix; small and independent, can realistically be done anytime, but do it early since it's a live exposure, not a feature gap.
7. **Payment credentials externalized + manual bank-slip workflow** (2.14) — independent of most other items; do early since it removes real (if currently dummy) secrets from the database.
8. **Download + login audit log, duplicate detection, GeoLite2 wiring** (2.9, 2.12) — one shared access-log design covers both; hooks into the same file-storage consolidation as step 1; the hash-based duplicate check is cheap once storage is unified.
9. **Plagiarism/similarity checking** (2.13) — internal-corpus check can ship early (no external dependency); external-provider integration depends on desk review (2.1) existing as the place results are shown.
10. **Author-facing submission status dashboard** (2.15) — design alongside 2.1/2.2/2.8/2.14 since it consumes their output, but the aggregation view itself is straightforward once they exist.
11. **Camera-ready stage + copyright-transfer collection** (3.3) — must exist before 2.5 can be correct, since proceedings need to pull the camera-ready version specifically, not just "latest."
12. **Proceedings real implementation + Zenodo deposit** (2.5) — depends on 2.1 (chair sign-off), 2.4 (ORCID metadata), and 3.3 (camera-ready version to actually publish), so sequenced last.
13. **Conference cloning UX** (2.6) — small, can slot in anytime, no dependencies.

(3.4 is not an action item — it's a validated confirmation that the product's scope is a genuine market gap, not redundant effort.)

Each numbered item above is sized to become its own brainstorm → spec → implementation-plan cycle, per the project's existing architectural workflow, rather than one combined plan.
