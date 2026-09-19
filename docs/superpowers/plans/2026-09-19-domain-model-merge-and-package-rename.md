# Domain Model Merge & Package Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the currently build-breaking dual-`@SpringBootApplication` bug, resolve every duplicated domain/repository/service class down to one canonical implementation each (keeping whichever side is actually wired in and working), then mechanically rename the whole package root from `com.icosiam` to `org.confcms` and move every surviving class into the feature-packaged folder structure (`auth/`, `submission/`, `review/`, `scheduling/`, `registration/`, `proceedings/`, `core/`, `web/`, `publicweb/`, `admin/`).

**Architecture:** This is corrective/mechanical work on an existing Spring Boot 3.5 / Java 21 monolith, not new feature work. It proceeds in three stages, each independently compilable and runnable: (1) fix the build-breaking entry-point conflict, (2) resolve duplicate classes one pair at a time (delete-the-loser, repoint imports, verify compile after each), (3) one mechanical package-rename-and-move pass across the now-deduplicated tree. Stage 3 happens last and only once, since it touches nearly every file — doing it before Stage 2 would mean touching every duplicated file twice.

**Tech Stack:** Spring Boot 3.5, Java 21, Gradle, JPA/Hibernate, MySQL (prod) / H2 (dev), Lombok. No test suite currently exists in this repo (`src/test` is absent) — this plan adds the first tests as part of the work it touches, per this project's standing testing requirements (TDD, 80%+ coverage on what's touched).

---

## Context for the engineer picking this up

This codebase is mid-migration from a flat package layout (`com.icosiam.cms.domain.*`, `.repository.*`, `.service.*`, `.security.*`, `.config.*`, `.controller.*`) to a feature-packaged layout (`com.icosiam.cms.auth.*`, `.submission.*`, `.review.*`, `.scheduling.*`, `.registration.*`, `.proceedings.*`, `.core.*`, `.web.*`, `.publicweb.*`, `.admin.*`). Both trees currently coexist. A full dependency mapping (done ahead of this plan) found that for several classes, the "legacy flat" side is actually the live, wired-in implementation, while the "feature-packaged" look-alike is an incomplete, unreferenced stub. The direction for this plan is: **keep whichever side of each duplicate pair is actually working today, regardless of which package it's currently in** — do not delete working code to satisfy a directional preference. The package-rename/reorganize move happens last, as a separate mechanical pass, once there is exactly one class per concept.

Full spec/rationale: `docs/superpowers/specs/2026-09-18-evaluation-and-gap-analysis.md`, section 1.2.

---

## Stage 1: Fix the build-breaking dual-entry-point bug

This is currently broken — `./gradlew resolveMainClassName` fails today with `Unable to find a single main class from the following candidates [com.icosiam.cms.ConferenceCmsApplication, com.icosiam.cms.web.CmsApplication]`. This must be fixed before anything else in this plan, since every later stage needs a working build to verify against.

### Task 1: Remove the duplicate `@SpringBootApplication` entry point

**Files:**
- Delete: `src/main/java/com/icosiam/cms/web/CmsApplication.java`
- Modify: `src/main/java/com/icosiam/cms/ConferenceCmsApplication.java`
- Modify: `src/main/resources/application-dev.properties`

- [ ] **Step 1: Confirm the build is currently broken (baseline)**

Run: `./gradlew resolveMainClassName`
Expected: FAILS with `Unable to find a single main class from the following candidates [com.icosiam.cms.ConferenceCmsApplication, com.icosiam.cms.web.CmsApplication]`

This confirms the starting state before the fix, so the next steps' success is meaningful.

- [ ] **Step 2: Delete the redundant entry point**

Delete `src/main/java/com/icosiam/cms/web/CmsApplication.java` entirely. `ConferenceCmsApplication` is kept because it already has `@EnableJpaAuditing` and an exclusion-filter pattern anticipating this cleanup.

- [ ] **Step 3: Remove the code-level bean-override escape hatch**

`ConferenceCmsApplication.java` currently reads:

```java
public static void main(String[] args) {
    org.springframework.boot.SpringApplication app = new org.springframework.boot.SpringApplication(ConferenceCmsApplication.class);
    app.setAllowBeanDefinitionOverriding(true);
    app.run(args);
}
```

Replace with:

```java
public static void main(String[] args) {
    SpringApplication.run(ConferenceCmsApplication.class, args);
}
```

This removes the `import org.springframework.boot.autoconfigure.SpringBootApplication;`-adjacent unused import too — check the top of the file: the `org.springframework.boot.SpringApplication` import is already present (it's used as a fully-qualified name in the old code), so just keep the existing `import org.springframework.boot.SpringApplication;` line and use the short name `SpringApplication` in the simplified method.

This escape hatch was masking duplicate bean definitions in **every** profile (not just dev, since it was set in code, not a profile-scoped property) — once Stage 2 removes the actual duplicate beans, this must not silently keep masking new ones that shouldn't exist.

- [ ] **Step 4: Remove the now-stale exclusion filter comment scope (leave the filter itself for now)**

`ConferenceCmsApplication`'s `@ComponentScan` excludes `com\.icosiam\.cms\.(core|proceedings|web\.config)\..*` — this exclusion is why classes under `core/`, `proceedings/`, and `web/config/` are not currently scanned at all when this class is the entry point. Leave this exclusion filter exactly as-is for now (do not edit it in this task) — Stage 2 will resolve each of those packages' duplicate classes deliberately, and the filter's contents will be revisited in Stage 2's tasks as each package is cleaned up. Removing it prematurely here would bring unresolved duplicate beans back into scope before they're fixed.

- [ ] **Step 5: Verify the build resolves a single entry point**

Run: `./gradlew resolveMainClassName`
Expected: BUILD SUCCESSFUL (no "unable to find a single main class" error)

- [ ] **Step 6: Verify the application still starts on the dev profile**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` (or `./run-dev.sh` if it sets this profile — check `run-dev.sh` first)
Expected: Application starts without throwing a `BeanDefinitionOverrideException` or similar startup failure. Stop it once you see the "Started ConferenceCmsApplication" log line (Ctrl+C).

Note: since `application-dev.properties` still has `spring.main.allow-bean-definition-overriding=true`, dev-profile startup will not yet fail even if duplicate beans remain — this property is intentionally left in place until Stage 2 removes the actual duplicates it was covering for. Do not remove it in this task.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/icosiam/cms/ConferenceCmsApplication.java
git rm src/main/java/com/icosiam/cms/web/CmsApplication.java
git commit -m "fix: remove duplicate @SpringBootApplication entry point

Two @SpringBootApplication classes coexisted (ConferenceCmsApplication and
web.CmsApplication), which broke ./gradlew resolveMainClassName outright
(\"Unable to find a single main class\"). Keeps ConferenceCmsApplication
(has @EnableJpaAuditing and an exclusion filter already anticipating the
legacy/feature package cleanup); removes the code-level
allowBeanDefinitionOverriding escape hatch that was masking this and any
other duplicate bean definitions in every profile, not just dev."
```

---

## Stage 2: Resolve each duplicate class pair

Each task below is independent and separately committable. Work through them in order — later tasks depend on earlier ones being done (e.g. the `User` task must land before anything touching `Review`/`ReviewAssignment`/`ReviewBid`, since those reference `User`). After every task, the project must still compile.

### Task 2: Resolve duplicate `BaseEntity` (byte-identical — trivial merge)

**Files:**
- Delete: `src/main/java/com/icosiam/cms/domain/BaseEntity.java`
- Keep as canonical (no change yet): `src/main/java/com/icosiam/cms/core/domain/BaseEntity.java`
- Modify (repoint imports): every file importing `com.icosiam.cms.domain.BaseEntity`

- [ ] **Step 1: Find every importer of the legacy `BaseEntity`**

Run: `grep -rl "import com.icosiam.cms.domain.BaseEntity;" src/main/java`
Expected output (verify against this exact list before proceeding — if it differs, stop and re-check rather than assuming):
```
src/main/java/com/icosiam/cms/domain/Conference.java
src/main/java/com/icosiam/cms/domain/ConferencePaymentConfig.java
src/main/java/com/icosiam/cms/domain/MagicLink.java
src/main/java/com/icosiam/cms/domain/SteeringCommitteeMember.java
src/main/java/com/icosiam/cms/domain/SubTheme.java
src/main/java/com/icosiam/cms/scheduling/domain/Session.java
src/main/java/com/icosiam/cms/scheduling/domain/Room.java
src/main/java/com/icosiam/cms/scheduling/domain/Presentation.java
src/main/java/com/icosiam/cms/review/domain/ReviewBid.java
```
(`domain/Paper.java`, `domain/Registration.java`, `domain/Review.java` also extend `BaseEntity` but via implicit same-package reference with no import statement — handle those in their own dedicated tasks below, not here.)

- [ ] **Step 2: Repoint every import found above**

In each file listed, replace:
```java
import com.icosiam.cms.domain.BaseEntity;
```
with:
```java
import com.icosiam.cms.core.domain.BaseEntity;
```

- [ ] **Step 3: Delete the legacy `BaseEntity`**

Delete `src/main/java/com/icosiam/cms/domain/BaseEntity.java`.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. If it fails, the error will name a file still referencing `com.icosiam.cms.domain.BaseEntity` (implicit same-package extends, e.g. `domain/Paper.java extends BaseEntity` with no import) — these are handled in their own tasks below (Task 6, Task 7), so if `compileJava` fails only on those known files, that's expected at this point; re-run after Task 6/7 land. If it fails on any file NOT in that known list, stop and investigate before continuing.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: merge duplicate BaseEntity, keep core.domain.BaseEntity"
```

### Task 3: Resolve duplicate `Role` (byte-identical — trivial merge)

**Files:**
- Delete: `src/main/java/com/icosiam/cms/domain/Role.java`
- Keep as canonical: `src/main/java/com/icosiam/cms/core/security/Role.java`
- Modify (repoint imports): every file importing `com.icosiam.cms.domain.Role`

- [ ] **Step 1: Find every importer**

Run: `grep -rl "import com.icosiam.cms.domain.Role;" src/main/java`
Expected to include at least: `repository/UserRepository.java`, `auth/service/AuthService.java`, `auth/repository/UserRepository.java`, `review/service/ReviewAssignmentService.java`, and any inline fully-qualified usages (also check `grep -rn "com\.icosiam\.cms\.domain\.Role\." src/main/java` for inline references like `com.icosiam.cms.domain.Role.ADMIN` in `submission/service/SubmissionService.java` and `com.icosiam.cms.domain.Role.valueOf(role)` in `web/controller/AuthRestController.java`).

- [ ] **Step 2: Repoint every import and inline reference found**

For files with an `import` statement: replace `import com.icosiam.cms.domain.Role;` with `import com.icosiam.cms.core.security.Role;`.

For files using the fully-qualified inline form (no import), e.g. in `submission/service/SubmissionService.java`:
```java
com.icosiam.cms.domain.Role.ADMIN
```
replace with:
```java
com.icosiam.cms.core.security.Role.ADMIN
```
and same pattern for `web/controller/AuthRestController.java`'s `com.icosiam.cms.domain.Role.valueOf(role)` → `com.icosiam.cms.core.security.Role.valueOf(role)`.

- [ ] **Step 3: Delete the legacy `Role`**

Delete `src/main/java/com/icosiam/cms/domain/Role.java`.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL (or failing only on `domain/User.java`, which still needs its own task below if not yet done — check the error output names only that file).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: merge duplicate Role enum, keep core.security.Role"
```

### Task 4: Resolve duplicate `PaymentStatus` (byte-identical — trivial merge)

**Files:**
- Delete: `src/main/java/com/icosiam/cms/domain/PaymentStatus.java`
- Keep as canonical: `src/main/java/com/icosiam/cms/registration/domain/PaymentStatus.java`
- Modify (repoint imports): every file importing `com.icosiam.cms.domain.PaymentStatus`

- [ ] **Step 1: Find every importer**

Run: `grep -rl "import com.icosiam.cms.domain.PaymentStatus;" src/main/java`

- [ ] **Step 2: Repoint each import**

Replace `import com.icosiam.cms.domain.PaymentStatus;` with `import com.icosiam.cms.registration.domain.PaymentStatus;` in each file found. (`domain/Registration.java` itself references `PaymentStatus` via same-package implicit access with no import — this is handled when `domain/Registration.java` is deleted in Task 7, not here.)

- [ ] **Step 3: Delete the legacy `PaymentStatus`**

Delete `src/main/java/com/icosiam/cms/domain/PaymentStatus.java`.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL, or failing only on `domain/Registration.java` (addressed in Task 7).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: merge duplicate PaymentStatus enum, keep registration.domain.PaymentStatus"
```

### Task 5: Port the `decision` field onto the live `Review` entity, then delete the dead `Review`/`ReviewDecision` POJOs

The legacy `domain.Review` (an unused plain POJO — confirmed zero importers) has a `decision: ReviewDecision` field and the `ReviewDecision` enum does not exist anywhere else. This is a real, well-designed piece (7-level accept/reject scale) worth keeping. Port it onto the live, JPA-mapped `review.domain.Review` entity before deleting the dead POJO, so this capability isn't silently lost.

**Files:**
- Create: `src/main/java/com/icosiam/cms/review/domain/ReviewDecision.java`
- Modify: `src/main/java/com/icosiam/cms/review/domain/Review.java`
- Test: `src/test/java/com/icosiam/cms/review/domain/ReviewTest.java`
- Delete: `src/main/java/com/icosiam/cms/domain/Review.java`
- Delete: `src/main/java/com/icosiam/cms/domain/ReviewDecision.java`

- [ ] **Step 1: Write the failing test for the new field**

Create `src/test/java/com/icosiam/cms/review/domain/ReviewTest.java`:

```java
package com.icosiam.cms.review.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewTest {

    @Test
    void canSetAndGetDecision() {
        Review review = new Review();
        review.setDecision(ReviewDecision.STRONG_ACCEPT);

        assertThat(review.getDecision()).isEqualTo(ReviewDecision.STRONG_ACCEPT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "com.icosiam.cms.review.domain.ReviewTest"`
Expected: FAILS to compile — `cannot find symbol: method setDecision` / `class ReviewDecision` (since neither exists yet in this package)

- [ ] **Step 3: Create the `ReviewDecision` enum in the `review` package**

Create `src/main/java/com/icosiam/cms/review/domain/ReviewDecision.java`:

```java
package com.icosiam.cms.review.domain;

public enum ReviewDecision {
    STRONG_ACCEPT,
    ACCEPT,
    WEAK_ACCEPT,
    BORDERLINE,
    WEAK_REJECT,
    REJECT,
    STRONG_REJECT
}
```

- [ ] **Step 4: Add the `decision` field to the live `Review` entity**

In `src/main/java/com/icosiam/cms/review/domain/Review.java`, add the import and field:

```java
package com.icosiam.cms.review.domain;

import com.icosiam.cms.domain.User;
import com.icosiam.cms.core.domain.BaseEntity;
import com.icosiam.cms.submission.domain.Paper;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "reviews")
@Getter
@Setter
public class Review extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_id", nullable = false)
    private Paper paper;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private User reviewer;

    @Column(nullable = false)
    private Integer score; // 1-5 or similar

    @Column(columnDefinition = "TEXT")
    private String comments;

    @Column(columnDefinition = "TEXT")
    private String confidentialComments;

    @Enumerated(EnumType.STRING)
    private ReviewDecision decision;
}
```

(Note: the `import com.icosiam.cms.domain.User;` line stays exactly as-is here — fixing that cross-wiring is Task 6, not this task. Do not change it yet.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.icosiam.cms.review.domain.ReviewTest"`
Expected: PASS

- [ ] **Step 6: Delete the now-fully-superseded legacy POJOs**

Delete `src/main/java/com/icosiam/cms/domain/Review.java` and `src/main/java/com/icosiam/cms/domain/ReviewDecision.java`. (Confirmed via the dependency mapping that nothing imports either of these — verify this yourself before deleting: `grep -rn "domain\.Review\b\|domain\.ReviewDecision\b" src/main/java --include="*.java" | grep -v "review\.domain\|review/domain"` should return no results referencing the legacy ones specifically.)

- [ ] **Step 7: Verify full compilation and test run**

Run: `./gradlew compileJava test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: port ReviewDecision onto the live Review entity, delete dead POJOs

domain.Review and domain.ReviewDecision were an unused plain-POJO stand-in
with zero importers, but carried a well-designed decision field/enum with
no equivalent on the real JPA-mapped review.domain.Review entity. Ports
the field and enum onto the live entity before deleting the dead code, so
per-review decision tracking isn't silently lost ahead of the chair/
co-chair desk-review work."
```

### Task 6: Resolve duplicate `User` — keep `domain.User`, delete the unused `auth.domain.User` stub

`domain.User` is a real `@Entity` with `provider`/`providerId` fields (needed for OAuth2 later) and a type-safe `Role` enum field, and is imported by nearly every controller/service in the app. `auth.domain.User` is a plain POJO (no `@Entity`, no `@Id`/`@GeneratedValue`), uses `role: String` instead of the enum, has no `provider`/`providerId` fields, and — confirmed via the dependency mapping — has zero importers anywhere in the codebase. Keep `domain.User`; delete the stub. (The package-rename pass in Stage 3 will later move `domain.User` into wherever the final auth package lands — this task only resolves the duplicate, it does not relocate anything yet.)

**Files:**
- Delete: `src/main/java/com/icosiam/cms/auth/domain/User.java`
- No changes needed to `domain/User.java` itself in this task.

- [ ] **Step 1: Confirm zero importers of the stub (verify before deleting — do not skip this check)**

Run: `grep -rn "auth\.domain\.User\b" src/main/java --include="*.java"`
Expected: no results (or only the file's own `package com.icosiam.cms.auth.domain;` declaration line inside `User.java` itself — if any *other* file imports it, stop and investigate why the earlier mapping was wrong before proceeding).

- [ ] **Step 2: Delete the unused stub**

Delete `src/main/java/com/icosiam/cms/auth/domain/User.java`.

- [ ] **Step 3: Fix the now-clearly-intentional cross-package references to `domain.User`**

This is not a bug to fix by changing the target — `domain.User` is staying as the canonical `User` for now. But confirm the two files flagged earlier as "cross-wiring bugs" now correctly and deliberately reference the surviving canonical class (no code change needed if they already import `com.icosiam.cms.domain.User` — just verify):

Run: `grep -n "import com.icosiam.cms.domain.User;" src/main/java/com/icosiam/cms/review/domain/ReviewAssignment.java src/main/java/com/icosiam/cms/review/domain/ReviewBid.java src/main/java/com/icosiam/cms/review/domain/Review.java`
Expected: all three print a matching import line — this confirms what looked like an accidental "wrong package" reference in the original evaluation was actually already pointing at the correct (surviving) class all along.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: delete unused auth.domain.User stub, keep domain.User as canonical

auth.domain.User was an incomplete, unreferenced stand-in (not a real JPA
@Entity, role stored as String not the type-safe Role enum, missing the
provider/providerId fields domain.User already has for OAuth2). Confirmed
zero importers before deleting. domain.User remains the one actually
wired into every controller and service."
```

### Task 7: Delete the remaining dead legacy POJOs (`Paper`, `Registration`) — confirmed zero importers

`domain.Paper` and `domain.Registration` are unused plain-POJO stand-ins for the real `submission.domain.Paper` and `registration.domain.Registration` JPA entities. Confirmed via the dependency mapping that nothing imports either. `domain.Paper.getReferenceCode()` is the one piece of unique logic on the dead POJO — confirmed nothing calls it either, so it is not ported (YAGNI: it can be re-added on `submission.domain.Paper` if/when a reference-code feature is actually needed, rather than guessed at now).

**Files:**
- Delete: `src/main/java/com/icosiam/cms/domain/Paper.java`
- Delete: `src/main/java/com/icosiam/cms/domain/PaperStatus.java`
- Delete: `src/main/java/com/icosiam/cms/domain/PaperVersion.java`
- Delete: `src/main/java/com/icosiam/cms/domain/Registration.java`

- [ ] **Step 1: Confirm zero importers of each (verify before deleting)**

Run: `grep -rn "domain\.Paper\b\|domain\.PaperStatus\b\|domain\.PaperVersion\b\|domain\.Registration\b" src/main/java --include="*.java" | grep -v "submission\.domain\|submission/domain\|registration\.domain\|registration/domain\|package com.icosiam.cms.domain;"`
Expected: no results outside the files themselves. If anything else shows up, stop and investigate before deleting that specific class.

- [ ] **Step 2: Delete the four confirmed-dead files**

Delete:
- `src/main/java/com/icosiam/cms/domain/Paper.java`
- `src/main/java/com/icosiam/cms/domain/PaperStatus.java`
- `src/main/java/com/icosiam/cms/domain/PaperVersion.java`
- `src/main/java/com/icosiam/cms/domain/Registration.java`

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: delete dead legacy Paper/PaperStatus/PaperVersion/Registration POJOs

Confirmed zero importers for all four — unused plain-POJO stand-ins for
the real JPA entities in submission.domain and registration.domain.
Paper.getReferenceCode(), the one unique piece of logic on the dead
Paper POJO, had no callers either and is not ported (can be re-added on
submission.domain.Paper if actually needed later)."
```

### Task 8: Resolve duplicate `CustomUserDetailsService` — keep `security.CustomUserDetailsService`, delete unused `auth.service.CustomUserDetailsService`

`security.CustomUserDetailsService` is the one actually wired into both `config.SecurityConfig` and `config.DevSecurityConfig`. `auth.service.CustomUserDetailsService` (bean name `"authCustomUserDetailsService"`, `@Profile("!dev")`, adds a `@Transactional(readOnly = true)` annotation) is confirmed unwired — nothing imports it. Its one improvement (`@Transactional(readOnly = true)` on the load method) is small and worth porting onto the surviving class.

**Files:**
- Modify: `src/main/java/com/icosiam/cms/security/CustomUserDetailsService.java`
- Delete: `src/main/java/com/icosiam/cms/auth/service/CustomUserDetailsService.java`

- [ ] **Step 1: Confirm zero importers of the unused one**

Run: `grep -rn "auth\.service\.CustomUserDetailsService\b" src/main/java --include="*.java"`
Expected: no results outside its own file.

- [ ] **Step 2: Read both files' load-user method to confirm the `@Transactional` difference before porting**

Run: `cat src/main/java/com/icosiam/cms/security/CustomUserDetailsService.java src/main/java/com/icosiam/cms/auth/service/CustomUserDetailsService.java`

Add `@Transactional(readOnly = true)` (with the matching `import org.springframework.transaction.annotation.Transactional;`) to the `loadUserByUsername` method (or equivalent) in `security/CustomUserDetailsService.java`, matching what the unused version already had.

- [ ] **Step 3: Delete the unused duplicate**

Delete `src/main/java/com/icosiam/cms/auth/service/CustomUserDetailsService.java`.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: delete unused auth.service.CustomUserDetailsService, keep security one

Confirmed zero importers for the auth-package version. Ported its one
improvement (@Transactional(readOnly = true) on the load method) onto
the surviving, actually-wired security.CustomUserDetailsService."
```

### Task 9: Resolve duplicate `SecurityConfig` — verify and fix the double-`SecurityFilterChain` risk

`config.SecurityConfig` (`@Configuration("securityConfigLegacy")`, `@Profile("!dev")`) and `web.config.SecurityConfig` (no visible `@Profile` guard) both define a `SecurityFilterChain` bean and both are currently in scope. This is a live risk of two competing filter chains, not just dead duplication — must be resolved deliberately, not assumed safe.

**Files:**
- Read and compare: `src/main/java/com/icosiam/cms/config/SecurityConfig.java`, `src/main/java/com/icosiam/cms/web/config/SecurityConfig.java`, `src/main/java/com/icosiam/cms/config/DevSecurityConfig.java`
- Delete one of the two (exact file determined in Step 1 below)
- Modify: whichever config's exclusion/profile setup needs adjusting

- [ ] **Step 1: Read all three security config files side by side and determine which is actually active**

Run: `cat src/main/java/com/icosiam/cms/config/SecurityConfig.java src/main/java/com/icosiam/cms/web/config/SecurityConfig.java src/main/java/com/icosiam/cms/config/DevSecurityConfig.java`

Recall from Stage 1: `ConferenceCmsApplication`'s `@ComponentScan` excludes `com\.icosiam\.cms\.(core|proceedings|web\.config)\..*` — meaning **`web.config.SecurityConfig` is currently NOT component-scanned at all** by the surviving entry point (`ConferenceCmsApplication`). So `config.SecurityConfig` (`@Profile("!dev")`) and `config.DevSecurityConfig` (`@Profile("dev")`) are the two that are actually active today, and `web.config.SecurityConfig` is dead code under the current entry point, despite looking newer.

- [ ] **Step 2: Confirm this with a runtime check before deleting anything**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'`, then in a separate terminal: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/login` (expect `200`), then stop the app (Ctrl+C).

This confirms the app boots with exactly the security config combination active today (`config.DevSecurityConfig` for dev profile) before you remove the excluded, dead `web.config.SecurityConfig`.

- [ ] **Step 3: Delete the dead, excluded `web.config.SecurityConfig`**

Delete `src/main/java/com/icosiam/cms/web/config/SecurityConfig.java`. (`config.SecurityConfig` and `config.DevSecurityConfig` remain — do not touch them in this task; they are the live, working configuration.)

- [ ] **Step 4: Verify compilation and startup**

Run: `./gradlew compileJava` — expect BUILD SUCCESSFUL.
Run: `./gradlew bootRun --args='--spring.profiles.active=dev'`, confirm it starts cleanly (Ctrl+C to stop).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: delete dead web.config.SecurityConfig

ConferenceCmsApplication's @ComponentScan already excludes web.config.*,
so web.config.SecurityConfig was never actually active despite defining
its own SecurityFilterChain bean -- confirmed by booting the app and
checking /login responds using only config.SecurityConfig +
config.DevSecurityConfig, the two that are genuinely in scope today."
```

### Task 10: Resolve duplicate `FileStorageService` — standardize on the per-conference-subfolder behavior

`service.FileStorageService` (2-arg `store(file, conferenceId)`, per-conference subfolder) is the one actually called by `submission.service.SubmissionService`. `core.service.FileStorageService` (1-arg `store(file)`, flat directory, bean name `"coreFileStorageService"`) is not currently called by anything found in the mapping — but since `core.*` is excluded from component scanning by `ConferenceCmsApplication` (same exclusion filter as Task 9), it is not even a live bean today regardless. Confirmed direction: keep the per-conference-subfolder behavior.

**Files:**
- Delete: `src/main/java/com/icosiam/cms/core/service/FileStorageService.java`
- Keep as canonical (no change needed): `src/main/java/com/icosiam/cms/service/FileStorageService.java`
- Verify: `src/main/java/com/icosiam/cms/submission/service/SubmissionService.java` (already correctly wired — confirm, don't change)

- [ ] **Step 1: Confirm `core.service.FileStorageService` has zero live importers**

Run: `grep -rn "core\.service\.FileStorageService\b" src/main/java --include="*.java"`
Expected: no results outside its own file.

- [ ] **Step 2: Confirm `SubmissionService` already uses the surviving 2-arg version**

Run: `grep -n "FileStorageService\|\.store(" src/main/java/com/icosiam/cms/submission/service/SubmissionService.java`
Expected: shows an import of `com.icosiam.cms.service.FileStorageService` and a `.store(file, conferenceId)`-shaped call (two arguments). If it instead shows the 1-arg core version, stop — this contradicts the mapping and needs investigation before proceeding.

- [ ] **Step 3: Delete the unused core version**

Delete `src/main/java/com/icosiam/cms/core/service/FileStorageService.java`.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: delete unused core.service.FileStorageService

Confirmed zero importers; also dead under ConferenceCmsApplication's
component-scan exclusion of core.*. Keeps service.FileStorageService's
per-conference-subfolder store(file, conferenceId), which is what
submission.service.SubmissionService actually calls today -- more
correct for a multi-conference product than a single flat upload
directory."
```

### Task 11: Resolve duplicate `ProceedingsService` — delete the byte-identical copy

Both `service.ProceedingsService` and `proceedings.service.ProceedingsService` were confirmed to be the same placeholder implementation (bare PDF-merge with blank cover/TOC pages), not one superseding the other. `proceedings.*` is also excluded from component scanning by `ConferenceCmsApplication` (same exclusion filter as Tasks 9–10), so `proceedings.service.ProceedingsService` is not even a live bean today. Keep `service.ProceedingsService`.

**Files:**
- Delete: `src/main/java/com/icosiam/cms/proceedings/service/ProceedingsService.java`
- Keep as canonical (no change needed): `src/main/java/com/icosiam/cms/service/ProceedingsService.java`

- [ ] **Step 1: Confirm nothing outside its own file references the proceedings-package version**

Run: `grep -rn "proceedings\.service\.ProceedingsService\b" src/main/java --include="*.java"`
Expected: no results outside its own file.

- [ ] **Step 2: Delete the duplicate**

Delete `src/main/java/com/icosiam/cms/proceedings/service/ProceedingsService.java`.

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: delete byte-identical duplicate proceedings.service.ProceedingsService

Both copies were the same placeholder PDF-merge implementation; the
proceedings package is also excluded from ConferenceCmsApplication's
component scan, so this copy was never a live bean. Real proceedings
generation is tracked separately as its own future feature (see the
evaluation doc, section 2.5) -- this task only removes duplication."
```

### Task 12: Resolve remaining repository shims (`PaperRepository`, `ReviewRepository`) — collapse to direct usage

`repository.PaperRepository` and `repository.ReviewRepository` are already pure passthrough interfaces (`interface PaperRepository extends com.icosiam.cms.submission.repository.PaperRepository {}`) — not real duplication, just an indirection layer. Since nothing requires the indirection (verify in Step 1), remove it and have callers depend on the real interface directly.

**Files:**
- Delete: `src/main/java/com/icosiam/cms/repository/PaperRepository.java`
- Delete: `src/main/java/com/icosiam/cms/repository/ReviewRepository.java`
- Modify: any file importing the shim interfaces (found in Step 1)

- [ ] **Step 1: Find every importer of the two shim interfaces**

Run: `grep -rln "import com.icosiam.cms.repository.PaperRepository;\|import com.icosiam.cms.repository.ReviewRepository;" src/main/java`

- [ ] **Step 2: Repoint each import to the real interface**

For each file found, replace:
```java
import com.icosiam.cms.repository.PaperRepository;
```
with:
```java
import com.icosiam.cms.submission.repository.PaperRepository;
```
and/or:
```java
import com.icosiam.cms.repository.ReviewRepository;
```
with:
```java
import com.icosiam.cms.review.repository.ReviewRepository;
```
as applicable per file.

- [ ] **Step 3: Delete the two shim files**

Delete `src/main/java/com/icosiam/cms/repository/PaperRepository.java` and `src/main/java/com/icosiam/cms/repository/ReviewRepository.java`.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: remove PaperRepository/ReviewRepository passthrough shims

These were pure indirection (interface X extends real.package.X {}) with
no behavior of their own. Repointed all callers to import the real
submission.repository.PaperRepository / review.repository.ReviewRepository
directly."
```

### Task 13: Verify the full test suite and full build after all Stage 2 duplicate resolutions

**Files:** none changed — verification only.

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass (including the `ReviewTest` added in Task 5).

- [ ] **Step 2: Run a full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Start the app once more on the dev profile as a final sanity check**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'`, confirm clean startup, hit `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/` (expect `200` or a redirect code), then stop (Ctrl+C).

No commit needed for this task — it's a verification checkpoint before Stage 3's larger mechanical move.

---

## Stage 3: Package rename (`com.icosiam` → `org.confcms`) and feature-package reorganization

This is a single mechanical pass, done last and only once every duplicate from Stage 2 is resolved — otherwise duplicated files would need this same treatment twice. At this point there is exactly one class per concept, so an IDE-assisted (or scripted) rename is safe.

### Task 14: Rename the Gradle group and update `build.gradle`

**Files:**
- Modify: `build.gradle`

- [ ] **Step 1: Change the Gradle group**

In `build.gradle`, change:
```groovy
group = "com.icosiam"
```
to:
```groovy
group = "org.confcms"
```

- [ ] **Step 2: Verify Gradle still configures correctly**

Run: `./gradlew help`
Expected: BUILD SUCCESSFUL (this only validates the Gradle file parses; it does not yet touch Java source, which is Task 15).

- [ ] **Step 3: Commit**

```bash
git add build.gradle
git commit -m "chore: rename Gradle group from com.icosiam to org.confcms"
```

### Task 15: Move every remaining `.java` file from `com.icosiam` to `org.confcms`, package-scan and adjust the exclusion filter

This step touches every remaining `.java` file under `src/main/java/com/icosiam` (86 files were counted before Stage 2's deletions; re-count after Stage 2 to know the exact scope at this point). Do this as a single scripted move rather than by hand, then fix up anything the script can't handle.

**Files:**
- Move: every file under `src/main/java/com/icosiam/**` to the equivalent path under `src/main/java/org/confcms/**`
- Modify: every moved file's `package` declaration and every `import com.icosiam.*` statement across the whole moved tree
- Modify: `src/main/resources/application.properties`, `src/main/resources/application-dev.properties` (the `logging.level.com.icosiam.cms=...` lines)
- Modify: `src/main/resources/data.sql` (no package reference to fix — the `icosiam.org` string found there is an email address, unrelated to the Java package; leave it as-is unless you're separately updating seed data, which is out of scope for this task)

- [ ] **Step 1: Record the exact file count before the move (sanity check baseline)**

Run: `find src/main/java/com/icosiam -name "*.java" | wc -l`

Note this number — it should match the count after the move (same files, new location), minus nothing (Stage 2 already did all deletions).

- [ ] **Step 2: Move the directory tree**

Run:
```bash
mkdir -p src/main/java/org/confcms
git mv src/main/java/com/icosiam/cms src/main/java/org/confcms/cms
```

(Using `git mv` preserves file history better than a plain `mv` + `git add`.)

- [ ] **Step 3: Rewrite every `package` declaration**

Run:
```bash
find src/main/java/org/confcms -name "*.java" -exec sed -i '' 's/^package com\.icosiam\./package org.confcms./' {} +
```
(macOS `sed -i ''` syntax — this matches the repo's `darwin` environment. If running on Linux, use `sed -i 's/^package com\.icosiam\./package org.confcms./'` without the empty string argument.)

- [ ] **Step 4: Rewrite every `import com.icosiam....` statement**

Run:
```bash
find src/main/java/org/confcms -name "*.java" -exec sed -i '' 's/import com\.icosiam\./import org.confcms./g' {} +
```

- [ ] **Step 5: Rewrite remaining fully-qualified inline references (no `import`, used directly in code)**

Run: `grep -rln "com\.icosiam" src/main/java/org/confcms --include="*.java"`

For each file listed, open it and replace any remaining `com.icosiam.` occurrence with `org.confcms.` — these are fully-qualified inline usages that a simple `import`-line sed won't catch (e.g. `@ComponentScan(basePackages = "com.icosiam.cms")`-style string literals in `ConferenceCmsApplication.java`, and any `com.icosiam.cms.X.Y` inline calls remaining from earlier tasks).

Specifically confirm and fix `src/main/java/org/confcms/cms/ConferenceCmsApplication.java`, which has string-literal package references:
```java
@EntityScan(basePackages = "com.icosiam.cms")
@ComponentScan(basePackages = "com.icosiam.cms",
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.icosiam\\.cms\\.(core|proceedings|web\\.config)\\..*"))
@EnableJpaRepositories(basePackages = "com.icosiam.cms")
```
must become:
```java
@EntityScan(basePackages = "org.confcms.cms")
@ComponentScan(basePackages = "org.confcms.cms",
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "org\\.confcms\\.cms\\.(core|proceedings|web\\.config)\\..*"))
@EnableJpaRepositories(basePackages = "org.confcms.cms")
```

- [ ] **Step 6: Update the logging package references in properties files**

In `src/main/resources/application.properties`, change:
```properties
logging.level.com.icosiam.cms=INFO
```
to:
```properties
logging.level.org.confcms.cms=INFO
```

In `src/main/resources/application-dev.properties`, change:
```properties
logging.level.com.icosiam.cms=DEBUG
```
to:
```properties
logging.level.org.confcms.cms=DEBUG
```

- [ ] **Step 7: Confirm nothing under `src/main` still references the old package**

Run: `grep -rn "com\.icosiam" src/main`
Expected: no results at all. (The `admin@icosiam.org` email address string in `data.sql` intentionally does NOT match `com\.icosiam` as a pattern since it's `icosiam.org` not `com.icosiam` — confirm this grep genuinely returns nothing before moving on; if it flags `data.sql`, re-check the exact string, since that email domain is unrelated to the Java package rename and out of scope here.)

- [ ] **Step 8: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. If it fails, the error output will show remaining `com.icosiam` references the sed passes missed — fix each one shown and re-run.

- [ ] **Step 9: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 10: Start the app on the dev profile as a final check**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'`, confirm clean startup with no `ClassNotFoundException`/`BeanCreationException`, hit `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/`, then stop (Ctrl+C).

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "refactor: rename package root from com.icosiam to org.confcms

Mechanical move of the entire remaining source tree, done last (after
Stage 2's duplicate-class resolution) so no file needed this treatment
twice. Updates every package declaration, import, fully-qualified inline
reference, and the two logging.level.* properties lines. This is the
last institution-specific naming tied to the original AIT/icosiam
project -- the codebase is now vendor-neutral, matching the single-
tenant self-hosted distributable-product framing from the evaluation
doc (docs/superpowers/specs/2026-09-18-evaluation-and-gap-analysis.md)."
```

---

## Self-review notes (for whoever executes this plan)

- **Stage ordering matters.** Stage 1 must land first (build is broken without it). Within Stage 2, Task 6 (`User`) must land before any task touching files that import `User` transitively breaks — but since Stage 2's tasks only delete already-confirmed-dead code and repoint imports to already-correct targets, task order within Stage 2 is otherwise flexible; the numbering above is a reasonable default, not a hard dependency chain except where a task's steps explicitly say "verify X from Task Y already landed."
- **Every deletion task has a "confirm zero importers" verification step before the delete.** Do not skip these even though the research mapping already checked this — code can change between when the mapping was done and when this plan is executed line-by-line.
- **No test suite exists in this repo today** (`src/test` is absent before this plan). Task 5 is the only place this plan adds a test, because it's the only task that changes behavior (adding a field) rather than purely deleting/moving code. Pure deletions and renames are verified by compilation and manual `bootRun` smoke checks, not new unit tests — writing tests asserting "this deleted class doesn't exist" would be meaningless.
- **This plan does not touch any of the feature-gap items** from the evaluation doc (chair/co-chair roles, reviewer decline flow, multi-auth, plagiarism checking, payment slip upload, etc.) — those are explicitly out of scope here and are each their own future brainstorm → spec → plan cycle, per that doc's recommended order of work (this plan corresponds to its item #1).
