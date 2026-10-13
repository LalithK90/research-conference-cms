# Deferred Findings

Real, non-blocking findings surfaced during final whole-branch reviews across this session's roadmap work. Each was deliberately left unfixed at the time — either genuinely minor, out of the originating branch's own scope, or a hardening/nice-to-have rather than a defect. Not to be acted on individually; revisit as one batch once the roadmap itself is complete.

---

## From: PDPA / Security Technical Baseline (roadmap #6)

- **C1 (fixed since):** `application.properties` used to force `spring.profiles.active=dev` as the default, meaning a plain boot never exercised the production-shaped profile. Fixed in a follow-up commit on `main` after the branch merged.
- **H2:** `/admin/users/new`'s role dropdown allowed minting a passwordless **ADMIN** account, authenticated solely via the pre-existing, unthrottled magic-link endpoint, with no audit trail. **Fixed since** (ADMIN removed from the dropdown, rejected server-side too).
- **H3 (fixed since):** `logging.level.org.springframework.security=DEBUG` was on by default, compounding the (spec-approved) plaintext-password-in-log design. Dropped to WARN in a follow-up commit.
- ~~M1-M4~~ (session hygiene, dismiss-prompt GET-vs-POST, missing input validation) — **all fixed** in the same follow-up pass as C1/H3.

*(Note: this branch's own deferred items were resolved within the same session, right after the user asked to "fix it now" — listed here only for a complete historical record.)*

## From: Access Audit Log + Duplicate-Paper Detection + GeoLite2 (roadmap #8)

- **Investigated and resolved (not a real issue):** the final review flagged, as an unconfirmed "worth checking" note, that Google OAuth2 login might use its `sub` ID rather than email as `authentication.getName()`, which would mean Google logins silently never appear in the access log (and every other email-based `getName()` lookup in the app would share the same gap). **Checked directly**: `CustomOAuth2UserService.loadUser` unconditionally sets the principal's name-attribute to `localUser.getEmail()` (line ~51, `NAME_ATTRIBUTE_KEY = "email"`), and neither Google nor ORCID's client registration requests the `openid` scope, so Spring's OIDC flow (which would otherwise bypass this custom service) never engages. `authentication.getName()` is always the real local email for every login path in this app. No fix needed — resolved as a non-issue.

## From: Spring Boot 3.5.8 → 4.1.1 Migration

- **M1 (narrowed further, still open):** Google and ORCID OAuth2 login still haven't been verified end-to-end with real credentials. Password login and magic-link login were both verified live under Spring Security 7. Re-confirmed again with dummy credentials in `application.properties` (now left in place, local-only/gitignored, with a `TODO` marking where real values go): both "Login with Google"/"Login with ORCID" buttons render, and both outbound redirects (`/oauth2/authorization/{google,orcid}`) carry the correct scope, PKCE challenge, and callback URI. What remains genuinely unverified is only the token-exchange/`CustomOAuth2UserService` step after a real provider login succeeds and calls back — a dummy client-id cannot reach that step (Google/ORCID reject it with `invalid_client` before any code is issued). The user will swap in real credentials; once that's done, a real login attempt through each provider should be done once to close this out.

## From: Scheduling & Program Builder (reviewer bidding + session scheduling roadmap)

- **Acknowledged, not a defect:** a review of the new scheduling feature flagged "no timezone handling" for session/presentation start and end times. Checked across the whole codebase: every date/time field anywhere in the app (`Conference.startDate`/`endDate`, `Registration`, `DecisionService`'s revision due dates, and now `Session`/`Presentation`) uses `LocalDate`/`LocalDateTime` with no timezone-aware type (`ZonedDateTime`/`OffsetDateTime`) anywhere. This is a pre-existing, app-wide implicit single-timezone assumption, not something scheduling introduced on its own. Adding real timezone support would mean a timezone field on `Conference`, a display-timezone policy, and converting every existing date/time read and write across the app — new architecture, not a bounded fix. Left as-is; revisit only as its own dedicated piece of work if multi-timezone conferences become a real requirement.

## From: Pre-launch readiness pass

- **Blocked on a real Spring Boot 4 compatibility issue, not attempted further:** tried to add `@WebMvcTest` smoke tests proving `/admin/users/**` and `/admin/conference/**` reject a non-admin through the real `SecurityFilterChain` (not just that `@PreAuthorize` is present in source). `@WebMvcTest(SomeController.class)` does not scope component scanning to that one controller in this app — it loads **every** `@Controller` bean app-wide, each pulling in its own JPA repository dependencies via `@EnableJpaRepositories` (declared directly on `ConferenceCmsApplication`, not through Boot's excludable auto-configuration), which then fails with no `EntityManagerFactory` in the slice. Root cause is almost certainly `ConferenceCmsApplication`'s custom `@ComponentScan(excludeFilters = ...)` conflicting with `@WebMvcTest`'s own slice `TypeExcludeFilter`. A real fix needs either restructuring how `@EnableJpaRepositories`/`@ComponentScan` are declared (moving them off the `@SpringBootApplication` class so Boot's own slicing can exclude them cleanly) or a custom test-slice annotation that works around the conflict — both bigger than a single test addition. `spring-boot-starter-webmvc-test` was added to `build.gradle` (needed for `@WebMvcTest`/`MockMvc` in Spring Boot 4, which split this out of `spring-boot-test-autoconfigure` into a web-stack-specific module) and left in place for whoever picks this up. In the meantime, admin-only access control on `/admin/users/**` and `/admin/conference/**` was verified live (anonymous → redirect to `/login`, non-admin → 403, admin → 200) against the real running app with real MySQL — confirmed working today, but only as a one-time manual check, not covered by the automated suite.

---

## How to use this file

When the full roadmap is complete (or whenever a dedicated "hardening pass" is scheduled), work through the items above as their own small brainstorm → bounded-fix cycle each — most are single-file, single-method changes. Delete an item from this file once it's fixed and merged, rather than marking it done in place, so this file only ever shows outstanding work.
