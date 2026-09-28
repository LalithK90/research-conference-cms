# Spring Boot 3.5.8 → 4.1.1 Migration — Design Spec

**Status:** Approved for implementation planning.

## 1. Problem

A manual commit (`a3cb736`, made directly by the user outside this session's normal process) bumped `build.gradle`'s Spring Boot plugin version from `3.5.8` to `4.1.1` and the Gradle wrapper from `9.2.1` to `9.7.1`, but changed nothing else. Spring Boot 4.1.1 is a real, current, generally-available release (GA line started November 2025; this patch released August 2026) — not a typo — but it is a **major version jump** (Spring Boot 4.x, built on Spring Framework 7, Spring Security 7, Jakarta EE 11). The build currently does not compile: `ConferenceCmsApplication.java` still imports `@EntityScan` from its old Spring Boot 3.x package, which moved in 4.0.

This spec covers migrating the application's source code to compile and run correctly against the already-bumped Spring Boot 4.1.1 / Gradle 9.7.1 versions.

## 2. Scope

**In scope:** every concrete breaking-change touchpoint confirmed by direct codebase survey (not assumed from generic release notes) to actually affect this app:

1. `build.gradle`: rename `spring-boot-starter-web` → `spring-boot-starter-webmvc` (the officially renamed starter artifact; the old name is deprecated but not yet removed, so this is a proactive rename, not a required fix to compile — do it anyway to avoid carrying a deprecated dependency forward).
2. `ConferenceCmsApplication.java`: `@EntityScan` import path move from `org.springframework.boot.autoconfigure.domain.EntityScan` to `org.springframework.boot.persistence.autoconfigure.EntityScan`. This is the one change actually blocking compilation today.
3. `SubmissionRestController.java`: Jackson 3 import migration. Spring Boot 4.1.1's default starters pull in Jackson 3, under which `com.fasterxml.jackson.databind.ObjectMapper` and `com.fasterxml.jackson.core.type.TypeReference` no longer exist — they moved to `tools.jackson.databind.ObjectMapper` and `tools.jackson.core.type.TypeReference` respectively (confirmed directly against Jackson's own `3.x` branch source and Spring's own "Introducing Jackson 3 support in Spring" blog post). This is the **only** file in the entire codebase importing anything from `com.fasterxml.jackson.*` — confirmed by a full-repo grep, not assumed.
4. Spring Security 6 → 7 re-verification: `SecurityConfig.java`, `DevSecurityConfig.java`, and the custom magic-link authentication filter family (`MagicLinkAuthenticationFilter`, `MagicLinkAuthenticationProvider`, `MagicLinkAuthenticationToken`, `PasswordPromptAuthenticationSuccessHandler`). No code changes are expected to be *required* here (both security config files already use the lambda DSL exclusively — the single most commonly-cited Security 7 migration cost, `.and()`-chained legacy config, does not exist anywhere in this codebase), but this area needs **manual, end-to-end verification** since Security 7's filter-chain/session-handling internals changed underneath stable-looking APIs like `.addFilterBefore(...)` and manual `SecurityContext` persistence via `HttpSessionSecurityContextRepository` — exactly what `MagicLinkAuthenticationFilter` hand-rolls (lines ~55-62: `request.changeSessionId()` + manual context persistence, bypassing the framework's normal `AuthenticationSuccessHandler` flow). A compile-clean build gives no assurance this still behaves correctly at runtime.
5. Full test suite must pass unmodified (or with only the mechanical import-path fixes from items 1-3, nothing test-logic-specific). Confirmed via direct repo survey that this codebase has **zero** `@SpringBootTest`, `@MockBean`, or `@SpyBean` usage anywhere — all 35 test classes are pure Mockito unit tests with no Spring `ApplicationContext` loading — so the commonly-cited Boot 4 test-infrastructure breakage (removed `@MockBean`/`@SpyBean`, changed `@SpringBootTest` MockMvc auto-configuration) **does not apply to this codebase at all**. (Note: `build.gradle`'s `test` task carries a comment referencing "@SpringBootTest context" and "280+ tests" that is stale/inaccurate relative to the actual current test suite — the comment is not touched by this migration, since removing a stale comment is out of scope for a version-migration spec, but implementers should not be confused by it into thinking `@SpringBootTest` usage exists somewhere it doesn't.)

**Out of scope (confirmed not applicable to this codebase by direct survey, not assumed):**
- JPA static metamodel / Criteria API migration (`hibernate-jpamodelgen` → `hibernate-processor`) — this codebase has zero metamodel classes and zero Criteria API usage anywhere.
- The `spring.dao.exceptiontranslation.enabled` → `spring.persistence.exceptiontranslation.enabled` properties-key rename — this key does not appear in either `application.properties` or `application-dev.properties`.
- Stray `javax.*` (pre-Jakarta-EE-9) namespace cleanup — the only `javax.*` imports found (`PaymentSecretConverter.java`) are `javax.crypto.*`, which is JDK standard library, not a Jakarta EE namespace, and is unaffected by this migration.
- Any change to `PaymentSecretConverter`, `PDFBox` usage, `GeoIP2` usage, email sending, or any other dependency with an explicit, non-Boot-managed version pin (`pdfbox:3.0.1`, `geoip2:4.3.0`) — these are unaffected by the Spring Boot BOM version and stay exactly as pinned.
- Any new feature work, refactor, or cleanup unrelated to making the app compile and run correctly under Spring Boot 4.1.1. This is a version-migration spec only.

## 3. Detailed changes

### 3.1 `build.gradle`

Change:
```groovy
implementation("org.springframework.boot:spring-boot-starter-web")
```
to:
```groovy
implementation("org.springframework.boot:spring-boot-starter-webmvc")
```

No other `build.gradle` change is needed — every other dependency in the file is either Boot-BOM-managed with no explicit version (Jackson, MySQL connector, H2, Lombok — these upgrade automatically with the plugin bump to whatever Boot 4.1.1's BOM specifies) or already has an explicit, non-Boot-managed pin that this migration doesn't touch (`pdfbox:3.0.1`, `geoip2:4.3.0`).

### 3.2 `ConferenceCmsApplication.java`

Change the import:
```java
import org.springframework.boot.autoconfigure.domain.EntityScan;
```
to:
```java
import org.springframework.boot.persistence.autoconfigure.EntityScan;
```
No other change needed in this file — the `@EntityScan(basePackages = "org.confcms.cms")` usage itself is unaffected by the package move, only the import statement changes.

### 3.3 `SubmissionRestController.java`

Change the imports:
```java
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
```
to:
```java
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
```

No change needed to the two usage sites (`private final ObjectMapper objectMapper = new ObjectMapper();` and `objectMapper.readValue(authorsJson, new TypeReference<List<AuthorRequestDto>>(){})`) — Jackson 3's `ObjectMapper` still has a working no-arg constructor and the identical `readValue(String, TypeReference<T>)` method signature under its new package.

One behavioral note to verify during implementation, not a required code change unless testing reveals a problem: Jackson 3's `readValue` throws `tools.jackson.core.JacksonException`, which **extends `RuntimeException`** (unchecked) rather than Jackson 2's `JsonProcessingException extends IOException` (checked). Read the surrounding `try`/`catch` block in `submitPaper` (currently `catch (Exception e) { return ResponseEntity.badRequest().body("Invalid authors JSON"); }`) — since it already catches the broad `Exception` type, not a Jackson-2-specific checked-exception type, this should continue to work unchanged, but confirm this by actually triggering the malformed-JSON path in manual verification (Section 5) rather than assuming from the diff alone.

### 3.4 Security re-verification (no code changes expected, verification-only)

Read `SecurityConfig.java`, `DevSecurityConfig.java`, `MagicLinkAuthenticationFilter.java`, `MagicLinkAuthenticationProvider.java`, `MagicLinkAuthenticationToken.java`, and `PasswordPromptAuthenticationSuccessHandler.java` in full. Confirm each still compiles cleanly against Spring Security 7 (pulled in transitively by the Boot 4.1.1 BOM bump — no explicit Security version is pinned in `build.gradle`, so this happens automatically). If compilation reveals an actual breaking API change in any of these files, that is a real, unplanned finding — document it precisely (exact class/method, exact error) and fix it with the minimal correct change for that specific API, rather than guessing at a fix in advance; this spec does not pre-authorize a specific fix for an API break that hasn't been confirmed to exist yet.

Regardless of whether compilation reveals any issue, **manual end-to-end verification of all three login paths this app supports is required** before this migration is considered complete (see Section 5) — a clean compile does not prove Security 7's internal session/filter-chain handling still behaves identically for the hand-rolled magic-link flow.

## 4. Testing

- Run the full existing test suite (`./gradlew test`) after each of the changes in Section 3 — it must stay green throughout, with no test-logic changes required (only the mechanical import fixes in 3.2/3.3 should be needed to get everything compiling and passing again).
- No new tests are required by this spec — this is a version migration with no behavior change intended anywhere except what's forced by the new package names, so existing test coverage is what verifies "nothing changed."

## 5. Manual verification (required, not optional)

Boot the dev profile (`SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun`) and manually exercise, end-to-end, with real HTTP requests against the running app:

1. **Password login** (the existing seeded `admin@example.com`/`admin` flow) — confirm login succeeds and lands on `/dashboard`.
2. **Magic-link login** — the highest-risk path per Section 3.4's analysis of `MagicLinkAuthenticationFilter`'s hand-rolled session/context handling. Trigger a real magic-link request, follow the link, confirm the session is correctly established and the user lands authenticated (not bounced back to `/login`).
3. **Google OAuth2 login** — if a usable OAuth2 client registration is configured in this dev environment; if not (e.g. no real Google client secret available in this sandbox), document this as a gap explicitly rather than silently skipping it, and note it in the deferred-findings doc for whoever has real OAuth2 credentials to verify later.
4. **The Jackson-touching endpoint** — `POST /submission` with a populated `authors` JSON field (the `SubmissionRestController.submitPaper` path that exercises `ObjectMapper.readValue(..., TypeReference<...>)`), confirming successful parsing; and a deliberately malformed `authors` JSON payload, confirming the existing `catch (Exception e)` block still correctly returns a 400 rather than an unhandled 500 (validates the unchecked-vs-checked exception-type change from Section 3.3 doesn't slip past the existing catch clause).
5. A general walk of a handful of already-verified pages from this session's prior work (e.g. the public conference website's `/about`, `/sponsors` pages) to catch any unexpected runtime regression outside the specifically-flagged areas — not exhaustive, just a sanity pass.

## 6. Open questions resolved during brainstorming

- **Is "Spring Boot 4.1.1" a real version, or a typo?** Confirmed real and GA (verified against official spring.io blog posts for the 4.0.0, 4.1.0, and 4.1.1 release announcements) — not a typo, not a pre-release.
- **How big is this migration actually?** Much smaller than a generic "major version bump" implies once checked against the actual codebase: 3 of the 9 originally-flagged risk categories (JPA metamodel/Criteria, the properties-key rename, and the `@SpringBootTest`/`@MockBean` test-infrastructure risk) don't apply to this codebase at all, confirmed by direct grep rather than assumption. The real, concrete touchpoints are one build.gradle line, one import-path fix, one file's Jackson imports, and a manual-verification pass over the security/login flows.
- **Does the security config need code changes for Security 7's mandatory lambda DSL?** No — both `SecurityConfig.java` and `DevSecurityConfig.java` were already fully lambda-DSL before this migration began, confirmed by reading both files in full. This removes the single most commonly-cited Security 7 migration cost for this specific codebase.
