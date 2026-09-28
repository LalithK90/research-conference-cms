# Spring Boot 3.5.8 → 4.1.1 Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the application compile and run correctly against the already-bumped Spring Boot 4.1.1 / Gradle 9.7.1 versions (`build.gradle` and `gradle-wrapper.properties` were already changed in a prior manual commit; this plan migrates the source code to match).

**Architecture:** Four small, mechanical changes (one build.gradle rename, one import-path fix, one file's Jackson imports) followed by a security-focused manual verification pass, since Spring Security 6→7's internal changes can't be caught by the compiler alone.

**Tech Stack:** Spring Boot 4.1.1, Java 21, Gradle 9.7.1, Spring Security 7, Jackson 3, JPA/Hibernate 7, Thymeleaf, JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-28-spring-boot-4-migration-design.md`

**Global Constraints:**
- This is a version-migration-only plan — no new features, no refactors, no cleanup beyond what's needed to compile and run correctly under the new versions. Do not "improve" unrelated code encountered along the way.
- If any task's compilation step reveals a breaking API change NOT already documented in this plan (i.e., something beyond the four specific changes below), that is a real, unplanned finding — stop, document the exact class/method/error precisely, and apply the minimal correct fix for that specific API change. Do not guess at a fix speculatively before confirming the actual compiler error.
- The `test` task's comment block in `build.gradle` (mentioning "@SpringBootTest context" and "280+ tests spinning up their own... context") is stale/inaccurate relative to the actual test suite (confirmed zero `@SpringBootTest` usage anywhere in this codebase) — do not let this comment mislead any task into thinking Spring context-loading tests exist. Do not "fix" this comment either; it's out of scope for a version-migration plan.
- Run `./gradlew test` after every task; once the build is compiling again (after Task 2), the suite must stay green for the rest of the plan.

---

### Task 1: `build.gradle` — rename `spring-boot-starter-web`

**Files:**
- Modify: `build.gradle`

- [ ] **Step 1: Rename the starter**

In `build.gradle`, change:
```groovy
implementation("org.springframework.boot:spring-boot-starter-web")
```
to:
```groovy
implementation("org.springframework.boot:spring-boot-starter-webmvc")
```

- [ ] **Step 2: Attempt to compile (expected to still fail — this is not the blocking error)**

Run: `./gradlew compileJava`
Expected: FAILS with the same `@EntityScan` error as before this task (`package org.springframework.boot.autoconfigure.domain does not exist` / `cannot find symbol: class EntityScan`, in `ConferenceCmsApplication.java`). This step exists only to confirm the starter rename itself introduced no NEW error — the pre-existing `@EntityScan` failure (fixed in Task 2) is expected to still be present.

If the error output is DIFFERENT from the `@EntityScan` error described above (e.g., a new "could not resolve dependency" error for the renamed starter), STOP — this is an unplanned finding (the rename may need a different artifact coordinate than the spec assumed). Document the exact error and do not proceed to Step 3 until resolved.

- [ ] **Step 3: Commit**

```bash
git add build.gradle
git commit -m "build: rename spring-boot-starter-web to spring-boot-starter-webmvc"
```

---

### Task 2: `ConferenceCmsApplication.java` — fix `@EntityScan` import path

**Files:**
- Modify: `src/main/java/org/confcms/cms/ConferenceCmsApplication.java`

- [ ] **Step 1: Fix the import**

In `src/main/java/org/confcms/cms/ConferenceCmsApplication.java`, change:
```java
import org.springframework.boot.autoconfigure.domain.EntityScan;
```
to:
```java
import org.springframework.boot.persistence.autoconfigure.EntityScan;
```

No other change to this file — the `@EntityScan(basePackages = "org.confcms.cms")` annotation usage itself is unaffected.

- [ ] **Step 2: Compile**

Run: `./gradlew compileJava`
Expected: this specific error is now gone. The build may still fail at this point due to Task 3's not-yet-fixed Jackson imports (`SubmissionRestController.java`) — if the ONLY remaining compile errors are Jackson-related (`package com.fasterxml.jackson.databind does not exist` / similar, specifically in `SubmissionRestController.java`), that is expected and matches this plan's next task; do not attempt to fix it here. If there is any OTHER compile error not related to Jackson imports in that one file, STOP and document it precisely — it is an unplanned finding per this plan's Global Constraints.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/confcms/cms/ConferenceCmsApplication.java
git commit -m "fix: update @EntityScan import path for Spring Boot 4"
```

---

### Task 3: `SubmissionRestController.java` — migrate to Jackson 3

**Files:**
- Modify: `src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java`

- [ ] **Step 1: Confirm this is still the only Jackson-importing file**

Run: `grep -rln "com.fasterxml.jackson" src/main/java src/test/java`
Expected: exactly one match, `src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java`. If any other file now appears (e.g. something merged in since the spec was written), read that file too and apply the same import fix from Step 2 to it as well — do not silently skip it.

- [ ] **Step 2: Update the imports**

In `src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java`, change:
```java
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
```
to:
```java
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
```

No other change needed in this file. The two usage sites (`private final ObjectMapper objectMapper = new ObjectMapper();` at the field declaration, and `objectMapper.readValue(authorsJson, new TypeReference<List<AuthorRequestDto>>(){})` inside `submitPaper`) work unchanged under the new package — Jackson 3's `ObjectMapper` still has a no-arg constructor and the identical `readValue(String, TypeReference<T>)` method signature.

- [ ] **Step 3: Read the surrounding try/catch block and confirm it still makes sense**

Read the full `submitPaper` method. Confirm the existing `catch (Exception e) { return ResponseEntity.badRequest().body("Invalid authors JSON"); }` block (or equivalent — read the actual current code, do not assume the exact wording) still wraps the `objectMapper.readValue(...)` call. Jackson 3's `readValue` throws `tools.jackson.core.JacksonException`, which extends `RuntimeException` (unchecked) rather than Jackson 2's checked `JsonProcessingException extends IOException` — since the existing catch clause already catches the broad `Exception` type (not a Jackson-2-specific checked type), this should require no code change. If the catch clause is narrower than `Exception` (e.g. specifically catches `IOException` or `JsonProcessingException`), that would be a real problem needing a fix — check this directly against the actual file contents rather than assuming from this plan's description.

- [ ] **Step 4: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL — this should be the last compile-blocking issue per the spec's survey. If it is not, document the new error precisely (per Global Constraints) before proceeding.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all existing tests passing (same count as before this migration — this plan changes no test logic, only compilation-blocking imports in production code). If any test fails, read the failure output carefully: a failure here is a real, unplanned finding (something behaves differently under Spring Boot 4.1.1 beyond the three purely mechanical import fixes made so far) — do not dismiss it as unrelated without checking.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java
git commit -m "fix: migrate SubmissionRestController to Jackson 3 imports"
```

---

### Task 4: Security re-verification — read, confirm compile-clean, no changes expected

**Files:** none modified (read-only verification task; only makes a change if a real, confirmed breaking API change is found)

- [ ] **Step 1: Read the security-related files in full**

Read these six files in full:
- `src/main/java/org/confcms/cms/config/SecurityConfig.java`
- `src/main/java/org/confcms/cms/config/DevSecurityConfig.java`
- `src/main/java/org/confcms/cms/security/MagicLinkAuthenticationFilter.java`
- `src/main/java/org/confcms/cms/security/MagicLinkAuthenticationProvider.java`
- `src/main/java/org/confcms/cms/security/MagicLinkAuthenticationToken.java`
- `src/main/java/org/confcms/cms/security/PasswordPromptAuthenticationSuccessHandler.java`

By this point (after Task 3), `./gradlew compileJava` already succeeded, which means all six of these files already compile cleanly against Spring Security 7 (pulled in transitively via the Boot 4.1.1 BOM — no explicit Security version is pinned anywhere in `build.gradle`). This step is confirming that fact by direct reading, not re-running the compiler (already done in Task 3, Step 4) — the goal is to build a mental model of exactly what `MagicLinkAuthenticationFilter`'s hand-rolled session/context-persistence logic does, ahead of Task 5's manual verification, which specifically needs to exercise this path.

- [ ] **Step 2: Confirm no code change is needed**

Since Task 3's `./gradlew compileJava` already succeeded with these files unmodified, no code change is expected or needed in this task. If, contrary to expectation, something about re-reading these files raises a concern that isn't a compile error (e.g., a deprecated-but-still-working API that Security 7's own changelog flags for future removal), note it in this task's ledger entry as a deferred/minor observation — do not make a speculative "just in case" change to security-critical code without a confirmed, concrete reason.

- [ ] **Step 3: Commit**

No commit for this task if no files were changed — this step only applies if Step 2 uncovered a genuine required fix, in which case commit that fix with a message describing the specific Security 7 API change found and corrected.

---

### Task 5: Manual end-to-end verification (all three login paths + Jackson endpoint)

**Files:** none (verification only)

- [ ] **Step 1: Final full test suite run**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, same test count as the pre-migration baseline (confirm the exact baseline count by checking `build/test-results/test/*.xml` totals — this plan does not hardcode an exact number since it depends on whatever the last-known-good count was immediately before this migration started).

- [ ] **Step 2: Boot the dev profile**

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun > /tmp/sb4-migration-boot.log 2>&1 &
```
Poll for `Started ConferenceCmsApplication` in the log (up to 60s), confirm no `ERROR`/`Exception`/`APPLICATION FAILED` lines.

- [ ] **Step 3: Verify password login**

Using real cookie-based login against the seeded dev admin (`admin@example.com` / `admin`, per this session's established working seed-password fix):
```bash
rm -f /tmp/sb4-cookies.txt /tmp/sb4-login.html
curl -s -c /tmp/sb4-cookies.txt -o /tmp/sb4-login.html http://localhost:8080/login
CSRF=$(grep -o 'name="_csrf" value="[^"]*"' /tmp/sb4-login.html | sed -E 's/.*value="([^"]*)"/\1/')
curl -s -b /tmp/sb4-cookies.txt -c /tmp/sb4-cookies.txt -D - -o /dev/null -X POST http://localhost:8080/login \
  --data-urlencode "username=admin@example.com" --data-urlencode "password=admin" \
  --data-urlencode "_csrf=$CSRF" | grep -i "^location"
```
Expected: `Location: http://localhost:8080/dashboard` (not `/login?error`).

- [ ] **Step 4: Verify magic-link login end-to-end**

The magic-link request endpoint is `POST /auth/magic/request` (in `AuthRestController.java`), taking a single `email` request param. It calls `MagicLinkService.createMagicLinkForEmail(email)`, builds a verify URL (`http://localhost:8080/auth/magic/verify?token=<token>`), and sends it via `EmailService.sendSimpleEmail`. In this sandbox, `application-dev.properties` points `spring.mail.host`/`spring.mail.port` at `localhost:1025` (a Mailhog/Mailpit-style dev SMTP catcher) which is almost certainly NOT running here — `EmailService.sendSimpleEmail`'s catch block falls back to `log.info("Email fallback to {} | subject={} | body={}", ...)` on send failure (confirmed in `EmailService.java`), which means the full email body — including the magic-link URL with its real token — will appear directly in `/tmp/sb4-migration-boot.log`. Trigger the request, then grep the log for the token:

```bash
curl -s -X POST "http://localhost:8080/auth/magic/request" --data-urlencode "email=admin@example.com"
sleep 2  # email dispatch may be async via EmailQueueService
MAGIC_URL=$(grep -o "http://localhost:8080/auth/magic/verify?token=[a-zA-Z0-9_-]*" /tmp/sb4-migration-boot.log | tail -1)
echo "MAGIC_URL: $MAGIC_URL"
```

If `MAGIC_URL` is empty, check the log more broadly (`grep -A2 "Email fallback" /tmp/sb4-migration-boot.log`) — the async `EmailQueueService` path may need more time, or may itself log differently; read `EmailQueueService.java` if the fallback log line isn't appearing as expected, since that's a real dependency this step relies on that hasn't been read yet.

Once you have the real token URL, visit it with a fresh cookie jar (`curl -s -c /tmp/sb4-magic-cookies.txt -D - -o /dev/null "$MAGIC_URL"`) and confirm the response redirects to an authenticated page (e.g. `/dashboard`), not back to `/login`. Then confirm the session is genuinely authenticated by following up with `curl -s -b /tmp/sb4-magic-cookies.txt http://localhost:8080/dashboard` and checking the response contains real dashboard content, not a login-page redirect. This is the highest-risk verification per the spec — `MagicLinkAuthenticationFilter`'s hand-rolled `request.changeSessionId()` + manual `SecurityContext` persistence via `HttpSessionSecurityContextRepository` is exactly the kind of internals-dependent code that a version bump can silently break without any compile error.

- [ ] **Step 5: Attempt Google OAuth2 login verification; document if not possible**

Check whether this dev environment has a real, usable Google OAuth2 client registration configured (check `application-dev.properties` / `application.properties` for `spring.security.oauth2.client.registration.google.client-id` and whether it's a placeholder/dummy value or a real one). If a real registration exists and can be exercised end-to-end (visiting `/oauth2/authorization/google` and completing a real Google login), do so and confirm successful authentication. If no real, usable Google OAuth2 credentials are available in this sandbox (the likely case), do NOT skip this silently — add an entry to `docs/superpowers/deferred-findings.md` under a new "From: Spring Boot 4.1.1 Migration" section noting that Google OAuth2 login was not manually verified end-to-end due to lack of real credentials in this environment, and should be verified by whoever has real Google OAuth2 credentials before this is considered fully confirmed safe in production.

- [ ] **Step 6: Verify the Jackson-touching endpoint**

Using the same authenticated cookie jar from Step 3 (an admin can submit a paper too, or use a seeded author account if `submitPaper` requires a specific role — check `SecurityConfig.java`'s `/submission/**` matcher rule found in Task 4 Step 1 to confirm which role is required, and log in as that role instead if `admin` doesn't have it):

Test the happy path — a `POST /submission` multipart request with a valid PDF file and a well-formed `authors` JSON array — and confirm it succeeds (200/201, not 500).

Test the malformed-JSON path — the same request but with a deliberately broken `authors` field (e.g. `authors=not-valid-json`) — and confirm the response is a 400 (`ResponseEntity.badRequest()...`), not an unhandled 500. This specifically validates that Jackson 3's unchecked `JacksonException` still gets caught by the existing broad `catch (Exception e)` block confirmed in Task 3, Step 3.

- [ ] **Step 7: Spot-check a couple of already-built pages for general regressions**

`curl` a couple of pages built earlier this session (e.g. `/about`, `/sponsors` from the public-conference-website work) and confirm 200 responses with real content, not error pages — this is a general sanity pass, not exhaustive re-verification of every feature this session built.

- [ ] **Step 8: Stop the app and clean up**

```bash
PID=$(lsof -i :8080 -sTCP:LISTEN -t)
kill -9 $PID
lsof -i :8080 -sTCP:LISTEN -t || echo "PORT FREE"
rm -f /tmp/sb4-cookies.txt /tmp/sb4-login.html /tmp/sb4-migration-boot.log
```

No commit for this task — it is verification only, unless Step 5's deferred-findings entry needs committing:

```bash
git add docs/superpowers/deferred-findings.md
git commit -m "docs: note Google OAuth2 login not manually verified after Spring Boot 4 migration (no real credentials in sandbox)"
```

(Only run this commit if Step 5 actually added such an entry — if real OAuth2 credentials WERE available and verification succeeded, skip this commit entirely.)

If any step in this task fails, fix the underlying issue in the relevant earlier task's files and re-run this task's steps from the top.
