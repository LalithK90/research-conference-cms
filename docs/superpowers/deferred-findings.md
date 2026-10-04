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

- **M4:** No DB-level uniqueness constraint enforces "one active registration per user per conference" — the in-code guard (`findByUserIdAndConferenceIdAndPaymentStatusNot`) can theoretically be raced by two concurrent `/register` submissions. Acceptable at current traffic levels; a partial unique index isn't available in MySQL, so this is a documented limitation, not a quick fix.

## From: Access Audit Log + Duplicate-Paper Detection + GeoLite2 (roadmap #8)

- **M2:** No test directly asserts duplicate-flag behavior specifically on the `uploadNewVersion`/`uploadRevision` code paths (only `submitPaper` has dedicated duplicate-detection tests) — though a prior review independently confirmed all three methods share identical, correctly-ordered wiring, so this is redundant coverage rather than an unverified risk.
- **Investigated and resolved (not a real issue):** the final review flagged, as an unconfirmed "worth checking" note, that Google OAuth2 login might use its `sub` ID rather than email as `authentication.getName()`, which would mean Google logins silently never appear in the access log (and every other email-based `getName()` lookup in the app would share the same gap). **Checked directly**: `CustomOAuth2UserService.loadUser` unconditionally sets the principal's name-attribute to `localUser.getEmail()` (line ~51, `NAME_ATTRIBUTE_KEY = "email"`), and neither Google nor ORCID's client registration requests the `openid` scope, so Spring's OIDC flow (which would otherwise bypass this custom service) never engages. `authentication.getName()` is always the real local email for every login path in this app. No fix needed — resolved as a non-issue.

## From: Camera-Ready Stage + Copyright-Transfer Collection (roadmap #11)

- **M7:** No `SubmissionRestControllerTest` exists for the new `POST /submission/{id}/camera-ready` REST endpoint (used by non-browser callers now that the HTML form posts elsewhere) — the plan explicitly permitted skipping this since no test file for this controller existed before this branch, relying on manual verification instead. A real gap against the originating spec's own testing section, not a regression.
- **M8:** `PaperVersion.cameraReady` is declared `@Column(nullable = false)` (a non-nullable primitive `boolean` with a Java-side default), which doesn't literally match the design spec's stated "nullable" reasoning for this field — harmless in practice (matches the existing `possibleDuplicate` field's declaration style exactly, and `data-dev.sql`'s seed rows were updated), just a documentation/spec-wording mismatch worth tidying if the spec is ever revisited.

## From: Public Conference Website (roadmap: AI-MERT replacement)

- **M5:** Visiting a Past Conferences detail page (`/past-conferences/{id}/about`, `/committee`, `/speakers`, `/sponsors`) correctly shows that historical conference's own content, but the shared nav bar (from `fragments/public_layout.html`) still links to the *active* conference's `/about`, `/speakers`, etc. -- a visitor browsing 2024's committee page who clicks "Speakers" in the nav lands on 2025's speakers page, not 2024's. Fixing this properly would mean threading the "am I viewing a past conference, and which one" context through the shared nav fragment, a real but non-trivial template change.

## From: Spring Boot 3.5.8 → 4.1.1 Migration

- **M1:** Google and ORCID OAuth2 login were NOT manually verified end-to-end after this migration — no real client credentials are configured in this sandbox (`spring.security.oauth2.client.registration.{google,orcid}.client-id`/`client-secret` are commented out in `application.properties`, along with the never-enabled Azure example). Password login and magic-link login were both verified live and confirmed working correctly under Spring Security 7. The final whole-branch review additionally confirmed, with a dummy `client-id`, that the OAuth2 redirect/PKCE wiring itself (`/oauth2/authorization/google` → correct redirect to Google, a forged callback correctly rejected) works under Security 7 — narrowing what's genuinely unverified to just the token-exchange/`CustomOAuth2UserService` path, which needs real credentials to test. Should be verified by whoever has real Google/ORCID OAuth2 credentials before this migration is considered fully confirmed safe for a production deployment that actually uses either login method.

---

## How to use this file

When the full roadmap is complete (or whenever a dedicated "hardening pass" is scheduled), work through the items above as their own small brainstorm → bounded-fix cycle each — most are single-file, single-method changes. Delete an item from this file once it's fixed and merged, rather than marking it done in place, so this file only ever shows outstanding work.
