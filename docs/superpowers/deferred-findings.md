# Deferred Findings

Real, non-blocking findings surfaced during final whole-branch reviews across this session's roadmap work. Each was deliberately left unfixed at the time — either genuinely minor, out of the originating branch's own scope, or a hardening/nice-to-have rather than a defect. Not to be acted on individually; revisit as one batch once the roadmap itself is complete.

---

## From: PDPA / Security Technical Baseline (roadmap #6)

- **C1 (fixed since):** `application.properties` used to force `spring.profiles.active=dev` as the default, meaning a plain boot never exercised the production-shaped profile. Fixed in a follow-up commit on `main` after the branch merged.
- **H2:** `/admin/users/new`'s role dropdown allowed minting a passwordless **ADMIN** account, authenticated solely via the pre-existing, unthrottled magic-link endpoint, with no audit trail. **Fixed since** (ADMIN removed from the dropdown, rejected server-side too).
- **H3 (fixed since):** `logging.level.org.springframework.security=DEBUG` was on by default, compounding the (spec-approved) plaintext-password-in-log design. Dropped to WARN in a follow-up commit.
- ~~M1-M4~~ (session hygiene, dismiss-prompt GET-vs-POST, missing input validation) — **all fixed** in the same follow-up pass as C1/H3.

*(Note: this branch's own deferred items were resolved within the same session, right after the user asked to "fix it now" — listed here only for a complete historical record.)*

## From: Payment Secrets Encryption + Bank-Slip Workflow (roadmap #7)

- **M1:** Bank slips (`RegistrationService`/`FileStorageService`) are stored in the same publicly-`permitAll()`'d `/uploads/**` directory as paper PDFs, served with content-type inferred from the file extension (not sniffed). A crafted filename (e.g. `x.html` containing valid PDF magic bytes) would pass `validateBankSlip` and be served back as `text/html` from the app's own origin — a real, if UUID-filename-mitigated, stored-XSS angle. This is a **pre-existing architectural pattern** (paper PDFs already work this way) that this branch extended to a new, more sensitive document type. Worth its own scoped fix: either move sensitive uploads out of the public static-resource path, or have the server choose the served content-type from the detected file type rather than the extension.
- **M2:** `AdminRegistrationController.viewSlip`'s `Content-Disposition` header is built via raw string concatenation with the user-supplied original filename — breaks on a `"` in the name, and non-ASCII filenames aren't RFC 6266-encoded (e.g. a Thai-language filename would display garbled). Fix: `ContentDisposition.inline().filename(name, StandardCharsets.UTF_8).build()`.
- **M3:** `RegistrationService.markAsPaid`/`reject` don't verify the registration's current status before transitioning it. Admin-only surface, low practical risk (requires a crafted request or a stale browser tab), but a one-line guard (`status == AWAITING_VERIFICATION`) would close it.
- **M4:** No DB-level uniqueness constraint enforces "one active registration per user per conference" — the in-code guard (`findByUserIdAndConferenceIdAndPaymentStatusNot`) can theoretically be raced by two concurrent `/register` submissions. Acceptable at current traffic levels; a partial unique index isn't available in MySQL, so this is a documented limitation, not a quick fix.

## From: Access Audit Log + Duplicate-Paper Detection + GeoLite2 (roadmap #8)

- **M1:** Re-uploading the exact same file (e.g. a legitimate revision that happens to be byte-identical to an earlier version) flags a paper as a "duplicate" of its own prior version — cosmetic noise for the chair, not a correctness bug. Fix: exclude matches where `matchedVersion.getPaper().getId().equals(paper.getId())` in `SubmissionService.applyDuplicateCheck`.
- **M2:** No test directly asserts duplicate-flag behavior specifically on the `uploadNewVersion`/`uploadRevision` code paths (only `submitPaper` has dedicated duplicate-detection tests) — though a prior review independently confirmed all three methods share identical, correctly-ordered wiring, so this is redundant coverage rather than an unverified risk.
- **M3:** `PaperVersion.possibleDuplicate` has no `@ColumnDefault("false")` — any future raw-SQL insert that omits the column will break, exactly as `data-dev.sql`'s pre-existing rows did (already fixed for the rows that exist today). Adding the column default would make this class of break impossible going forward, not just patched for the current seed data.
- **Investigated and resolved (not a real issue):** the final review flagged, as an unconfirmed "worth checking" note, that Google OAuth2 login might use its `sub` ID rather than email as `authentication.getName()`, which would mean Google logins silently never appear in the access log (and every other email-based `getName()` lookup in the app would share the same gap). **Checked directly**: `CustomOAuth2UserService.loadUser` unconditionally sets the principal's name-attribute to `localUser.getEmail()` (line ~51, `NAME_ATTRIBUTE_KEY = "email"`), and neither Google nor ORCID's client registration requests the `openid` scope, so Spring's OIDC flow (which would otherwise bypass this custom service) never engages. `authentication.getName()` is always the real local email for every login path in this app. No fix needed — resolved as a non-issue.

---

## How to use this file

When the full roadmap is complete (or whenever a dedicated "hardening pass" is scheduled), work through the items above as their own small brainstorm → bounded-fix cycle each — most are single-file, single-method changes. Delete an item from this file once it's fixed and merged, rather than marking it done in place, so this file only ever shows outstanding work.
