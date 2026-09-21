# Multi-Auth (Password Reset, Magic Link, Google & ORCID OAuth2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Formalize magic link into the real Spring Security filter chain, add password reset, and wire up Google + ORCID OAuth2 login, all sharing one `UserIdentity` linking table so a person can have multiple login methods on one account.

**Architecture:** A new `UserIdentity` entity records every linked login method (`local`/`google`/`orcid`) per `User`. Password reset gets its own token entity (`PasswordResetToken`, mirroring `PersonInvitation`'s existing pattern). Magic link becomes a real `AuthenticationProvider` + filter pair instead of a REST controller manually poking `SecurityContextHolder`. OAuth2 login uses Spring Security's native `oauth2Login()` with one shared `CustomOAuth2UserService` for both providers, each individually enable/disable-able via `@ConditionalOnProperty`. A new `AccountSettingsController`/page lets a logged-in user see and add linked methods.

**Tech Stack:** Spring Boot 3.5.8, Spring Security 6 (`spring-boot-starter-oauth2-client`, already a dependency), Spring Data JPA, Thymeleaf, Lombok, JUnit 5 + Mockito + AssertJ (this codebase's only test style — no `@SpringBootTest`/`MockMvc` anywhere, and this plan does not introduce any).

**Global Constraints** (binding on every task, from `docs/superpowers/specs/2026-09-21-multi-auth-design.md`):
- Every new `UserIdentity` row's `provider` value is a lowercase string literal: `"local"`, `"google"`, `"orcid"` — used consistently everywhere, no enum (matches this codebase's existing style of plain string `provider`/`purpose` fields elsewhere, e.g. `User.provider` today).
- OAuth2 email must be verified before any account action (create or link) — reject otherwise.
- Password-reset "forgot password" responses are identical whether or not the email matched an account (enumeration prevention) — never branch response shape/timing-observably on match/no-match.
- All new tokens (`PasswordResetToken`) generated via `UUID.randomUUID().toString()` inline at the issuance site, matching `PersonInvitationService`/`MagicLinkService` precedent — no new token utility class.
- New auth-related controllers go in `org.confcms.cms.web.controller` (no new `auth.controller` package) — matches where `AuthRestController` already lives, per confirmed codebase convention.
- New service classes for this feature go in `org.confcms.cms.auth.service` (matches `AuthService`/`MagicLinkService`'s existing location).
- Repository interfaces follow the exact existing style: a bare interface extending `JpaRepository<Entity, Long>` with only the query methods actually needed, in `org.confcms.cms.repository`.
- Every new entity extends `BaseEntity` (`id`, `createdAt`, `updatedAt` via `@CreatedDate`/`@LastModifiedDate`, already auditing-enabled via `@EntityListeners(AuditingEntityListener.class)` — confirm JPA auditing is enabled application-wide before assuming `@CreatedDate` populates automatically; check `@EnableJpaAuditing` presence, Task 1).
- No `@SpringBootTest`, no `MockMvc`, no new test frameworks — pure Mockito unit tests on services/providers, `@ExtendWith(MockitoExtension.class)`, AssertJ assertions, matching every existing test file in this codebase.
- `ddl-auto=update` (default profile) will NOT drop columns — dropping `User.provider`/`providerId` requires a manual SQL step, documented explicitly in the task that does it (Task 2), not silently assumed.

---

### Task 1: Confirm JPA auditing is enabled; `UserIdentity` entity + repository

**Files:**
- Read (verify only, no edit unless missing): `src/main/java/org/confcms/cms/ConferenceCmsApplication.java` (or wherever `@SpringBootApplication` lives) for `@EnableJpaAuditing`
- Create: `src/main/java/org/confcms/cms/domain/UserIdentity.java`
- Create: `src/main/java/org/confcms/cms/repository/UserIdentityRepository.java`
- Test: `src/test/java/org/confcms/cms/repository/UserIdentityRepositoryTest.java` — **not created**; this codebase has zero repository-level tests anywhere (confirmed: only service/controller unit tests exist) — an entity+repository pair with no custom query logic beyond a derived-query method needs no test of its own, consistent with how `MagicLink`/`PersonInvitation` entities were added in prior plans without a dedicated repository test.

- [ ] **Step 1: Confirm `@EnableJpaAuditing` is present**

Run: `grep -rn "EnableJpaAuditing" src/main/java/`
Expected: one match, on the `@SpringBootApplication` class or a `@Configuration` class. `BaseEntity`'s `@CreatedDate`/`@LastModifiedDate` only populate if this annotation exists somewhere in the application context.

If the grep finds nothing, STOP this task and report it — this would mean every existing entity's `createdAt`/`updatedAt` has been silently null all along, which is a finding for the whole codebase, not something to quietly work around in this one task. (Expected outcome: the grep finds it, since `PersonInvitation`/`MagicLink` already rely on the same `BaseEntity` and appear to work correctly in prior features' tests — this step is a confirmation, not expected to fail.)

- [ ] **Step 2: Create the `UserIdentity` entity**

Create `src/main/java/org/confcms/cms/domain/UserIdentity.java`:

```java
package org.confcms.cms.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.confcms.cms.core.domain.BaseEntity;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_identities", uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "provider_user_id"}))
@Getter
@Setter
public class UserIdentity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String provider; // "local" | "google" | "orcid"

    @Column(name = "provider_user_id")
    private String providerUserId; // Google's "sub", ORCID's iD; null for "local"

    @Column(nullable = false)
    private LocalDateTime linkedAt;
}
```

- [ ] **Step 3: Create the repository**

Create `src/main/java/org/confcms/cms/repository/UserIdentityRepository.java`:

```java
package org.confcms.cms.repository;

import org.confcms.cms.domain.UserIdentity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, Long> {
    Optional<UserIdentity> findByProviderAndProviderUserId(String provider, String providerUserId);
    List<UserIdentity> findByUserId(Long userId);
    boolean existsByUserIdAndProvider(Long userId, String provider);
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/domain/UserIdentity.java src/main/java/org/confcms/cms/repository/UserIdentityRepository.java
git commit -m "feat: add UserIdentity entity for multi-provider login linking

One row per linked login method (local/google/orcid) per User, unique
on (provider, provider_user_id) so a given provider account can only
ever be linked to one local User. Foundation for auto-linking Google
and ORCID logins to an existing password account by verified email."
```

---

### Task 2: `User.orcidId` field; drop `provider`/`providerId`

**Files:**
- Modify: `src/main/java/org/confcms/cms/domain/User.java`
- Modify: `src/main/java/org/confcms/cms/repository/UserRepository.java`
- Modify: `src/main/resources/data-dev.sql` (dev-profile schema has no auto-migration; must be updated directly)
- Test: no new test — this is a pure structural field change with no new behavior; existing `UserRepository`/`User`-constructing tests elsewhere are unaffected by an unused-field removal (grep confirms `provider`/`providerId` are read/written nowhere outside the declaration and `findByProviderAndProviderId`, both being removed together in this task)

- [ ] **Step 1: Confirm no other test or code references the fields being removed**

Run: `grep -rn "\.getProvider()\|\.setProvider(\|\.getProviderId()\|\.setProviderId(\|findByProviderAndProviderId" src/`
Expected: matches only inside `User.java` (the field/getter/setter declarations themselves) and `UserRepository.java` (the method declaration) — both files this task modifies. If any OTHER file references these, STOP and report it — the design spec's "confirmed unused" claim would be wrong and this task needs to handle that caller first.

- [ ] **Step 2: Edit `User.java`**

Read `src/main/java/org/confcms/cms/domain/User.java` first to get its exact current field block. Remove the `provider` and `providerId` fields entirely (with their comment `// google, microsoft, local` and `@Column` annotations, whatever they currently are). Add, in their place:

```java
    private String orcidId; // nullable; populated on ORCID OAuth2 login/link, denormalized from UserIdentity for cheap author-disambiguation lookups later
```

- [ ] **Step 3: Edit `UserRepository.java`**

Remove the `Optional<User> findByProviderAndProviderId(String provider, String providerId);` line entirely.

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. (If this fails because some other file DID reference the removed members despite Step 1's grep, STOP — Step 1 should have caught it; investigate why it didn't before proceeding.)

- [ ] **Step 5: Run the full existing test suite**

Run: `./gradlew test`
Expected: all existing tests still pass (no test constructs `User` with `.setProvider(...)`/`.setProviderId(...)`, confirmed by Step 1's grep).

- [ ] **Step 6: Update dev schema**

Read `src/main/resources/data-dev.sql` to find the `users` table `CREATE TABLE` statement. Remove the `provider` and `provider_id` (or however they're named in SQL — check exact column names, likely `provider`/`provider_id`) column definitions, and add `orcid_id VARCHAR(255)` (nullable, no constraint). Verify no `INSERT INTO users` statement references a `provider`/`provider_id` column by position or name — if any do (e.g. `INSERT INTO users (email, ..., provider, provider_id, ...)`), update those column lists too.

- [ ] **Step 7: Verify dev profile boots correctly**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` in the background. Confirm no SQL/Hibernate startup errors in the log (schema/entity mismatch would surface immediately at boot as a `SchemaManagementException` or similar). Stop the app afterward.

- [ ] **Step 8: Document the production-profile manual migration step**

Add a one-line note to this plan's own text is not appropriate (plans don't get edited after writing) — instead, add a short comment block at the top of `src/main/resources/application.properties` near the `ddl-auto=update` line:

```properties
# NOTE: ddl-auto=update does not drop columns. When deploying the multi-auth
# feature (2026-09-21), run manually against production:
#   ALTER TABLE users DROP COLUMN provider, DROP COLUMN provider_id;
#   ALTER TABLE users ADD COLUMN orcid_id VARCHAR(255);
```

(Read the file first to place this sensibly near the existing `ddl-auto` line, not just appended at the end.)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/confcms/cms/domain/User.java src/main/java/org/confcms/cms/repository/UserRepository.java src/main/resources/data-dev.sql src/main/resources/application.properties
git commit -m "feat: replace unused User.provider/providerId with orcidId

provider/providerId were dead fields (zero reads/writes outside their
own declaration and one now-removed repository method) predating the
UserIdentity linking model. orcidId is denormalized from UserIdentity
for cheap future author-disambiguation lookups. ddl-auto=update won't
drop the old columns automatically -- manual production migration step
documented in application.properties."
```

---

### Task 3: `PasswordResetToken` entity + repository + service (TDD)

**Files:**
- Create: `src/main/java/org/confcms/cms/domain/PasswordResetToken.java`
- Create: `src/main/java/org/confcms/cms/repository/PasswordResetTokenRepository.java`
- Create: `src/main/java/org/confcms/cms/auth/service/PasswordResetService.java`
- Test: `src/test/java/org/confcms/cms/auth/service/PasswordResetServiceTest.java` (new)

- [ ] **Step 1: Create the entity**

Create `src/main/java/org/confcms/cms/domain/PasswordResetToken.java`:

```java
package org.confcms.cms.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.confcms.cms.core.domain.BaseEntity;

import java.time.LocalDateTime;

@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
public class PasswordResetToken extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private boolean used;
}
```

- [ ] **Step 2: Create the repository**

Create `src/main/java/org/confcms/cms/repository/PasswordResetTokenRepository.java`:

```java
package org.confcms.cms.repository;

import org.confcms.cms.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByToken(String token);
    Optional<PasswordResetToken> findFirstByUserIdOrderByCreatedAtDesc(Long userId);
}
```

- [ ] **Step 3: Write the failing tests**

Create `src/test/java/org/confcms/cms/auth/service/PasswordResetServiceTest.java`:

```java
package org.confcms.cms.auth.service;

import org.confcms.cms.domain.PasswordResetToken;
import org.confcms.cms.domain.User;
import org.confcms.cms.domain.UserIdentity;
import org.confcms.cms.repository.PasswordResetTokenRepository;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordResetTokenRepository tokenRepository;
    @Mock
    private UserIdentityRepository userIdentityRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private PasswordEncoder passwordEncoder;

    private PasswordResetService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(userRepository, tokenRepository, userIdentityRepository, emailService, passwordEncoder);

        user = new User();
        user.setId(1L);
        user.setEmail("author@example.com");
    }

    @Test
    void requestResetForExistingEmailCreatesTokenAndSendsEmail() {
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.empty());
        when(tokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.requestReset("author@example.com");

        ArgumentCaptor<PasswordResetToken> captor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isEqualTo(user);
        assertThat(captor.getValue().isUsed()).isFalse();
        assertThat(captor.getValue().getExpiresAt()).isAfter(LocalDateTime.now());

        verify(emailService).sendSimpleEmail(eq("author@example.com"), any(), any());
    }

    @Test
    void requestResetForNonexistentEmailDoesNotThrowAndSendsNoEmail() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        service.requestReset("nobody@example.com");

        verify(emailService, never()).sendSimpleEmail(any(), any(), any());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void requestResetWithinCooldownDoesNotSendSecondEmail() {
        PasswordResetToken recent = new PasswordResetToken();
        recent.setUser(user);
        recent.setExpiresAt(LocalDateTime.now().plusHours(2));
        recent.setUsed(false);
        // createdAt is set by JPA auditing in production; simulate a recent token by
        // making requestReset's cooldown check rely on expiresAt/used, not createdAt,
        // since createdAt is not settable pre-persist in a unit test -- see Step 5 note.

        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));
        when(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(1L)).thenReturn(Optional.of(recent));

        service.requestReset("author@example.com");

        verify(emailService, never()).sendSimpleEmail(any(), any(), any());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void validateTokenRejectsUnknownToken() {
        when(tokenRepository.findByToken("bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateToken("bad-token"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateTokenRejectsExpiredToken() {
        PasswordResetToken expired = new PasswordResetToken();
        expired.setUser(user);
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        expired.setUsed(false);
        when(tokenRepository.findByToken("expired-token")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.validateToken("expired-token"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateTokenRejectsUsedToken() {
        PasswordResetToken used = new PasswordResetToken();
        used.setUser(user);
        used.setExpiresAt(LocalDateTime.now().plusHours(1));
        used.setUsed(true);
        when(tokenRepository.findByToken("used-token")).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> service.validateToken("used-token"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resetPasswordSetsHashMarksTokenUsedAndEnsuresLocalIdentity() {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setToken("valid-token");
        token.setExpiresAt(LocalDateTime.now().plusHours(1));
        token.setUsed(false);

        when(tokenRepository.findByToken("valid-token")).thenReturn(Optional.of(token));
        when(passwordEncoder.encode("NewPassw0rd!")).thenReturn("hashed-value");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userIdentityRepository.existsByUserIdAndProvider(1L, "local")).thenReturn(false);
        when(userIdentityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resetPassword("valid-token", "NewPassw0rd!");

        assertThat(user.getPasswordHash()).isEqualTo("hashed-value");
        assertThat(token.isUsed()).isTrue();
        verify(tokenRepository).save(token);

        ArgumentCaptor<UserIdentity> identityCaptor = ArgumentCaptor.forClass(UserIdentity.class);
        verify(userIdentityRepository).save(identityCaptor.capture());
        assertThat(identityCaptor.getValue().getProvider()).isEqualTo("local");
        assertThat(identityCaptor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void resetPasswordDoesNotDuplicateExistingLocalIdentity() {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setToken("valid-token");
        token.setExpiresAt(LocalDateTime.now().plusHours(1));
        token.setUsed(false);

        when(tokenRepository.findByToken("valid-token")).thenReturn(Optional.of(token));
        when(passwordEncoder.encode(any())).thenReturn("hashed-value");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userIdentityRepository.existsByUserIdAndProvider(1L, "local")).thenReturn(true);

        service.resetPassword("valid-token", "NewPassw0rd!");

        verify(userIdentityRepository, never()).save(any());
    }
}
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.auth.service.PasswordResetServiceTest"`
Expected: FAILS to compile — `PasswordResetService` doesn't exist yet.

- [ ] **Step 5: Implement `PasswordResetService`**

Create `src/main/java/org/confcms/cms/auth/service/PasswordResetService.java`:

```java
package org.confcms.cms.auth.service;

import org.confcms.cms.domain.PasswordResetToken;
import org.confcms.cms.domain.User;
import org.confcms.cms.domain.UserIdentity;
import org.confcms.cms.repository.PasswordResetTokenRepository;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final Duration TOKEN_VALIDITY = Duration.ofHours(2);
    private static final Duration REQUEST_COOLDOWN = Duration.ofMinutes(5);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final UserIdentityRepository userIdentityRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public void requestReset(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);
        if (userOpt.isEmpty()) {
            return; // enumeration-safe: silent no-op, same as the "success" path from the caller's perspective
        }
        User user = userOpt.get();

        Optional<PasswordResetToken> recent = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId());
        if (recent.isPresent() && !recent.get().isUsed()
                && recent.get().getExpiresAt().isAfter(LocalDateTime.now().plus(TOKEN_VALIDITY).minus(REQUEST_COOLDOWN))) {
            return; // within cooldown window of the most recent still-valid token; silent no-op
        }

        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setToken(UUID.randomUUID().toString());
        token.setExpiresAt(LocalDateTime.now().plus(TOKEN_VALIDITY));
        token.setUsed(false);
        tokenRepository.save(token);

        String link = "/auth/reset-password?token=" + token.getToken();
        emailService.sendSimpleEmail(user.getEmail(), "Password reset requested",
                "Click the link below to reset your password (valid for 2 hours):\n\n" + link
                        + "\n\nIf you did not request this, you can safely ignore this email.");
    }

    @Transactional(readOnly = true)
    public PasswordResetToken validateToken(String tokenValue) {
        PasswordResetToken token = tokenRepository.findByToken(tokenValue)
                .orElseThrow(() -> new IllegalArgumentException("This link is invalid, expired, or already used"));

        if (token.isUsed() || token.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("This link is invalid, expired, or already used");
        }

        return token;
    }

    @Transactional
    public void resetPassword(String tokenValue, String newPassword) {
        PasswordResetToken token = validateToken(tokenValue);
        User user = token.getUser();

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        token.setUsed(true);
        tokenRepository.save(token);

        if (!userIdentityRepository.existsByUserIdAndProvider(user.getId(), "local")) {
            UserIdentity identity = new UserIdentity();
            identity.setUser(user);
            identity.setProvider("local");
            identity.setLinkedAt(LocalDateTime.now());
            userIdentityRepository.save(identity);
        }
    }
}
```

**Note on the cooldown check's exact expression** (`recent.get().getExpiresAt().isAfter(LocalDateTime.now().plus(TOKEN_VALIDITY).minus(REQUEST_COOLDOWN))`): since `expiresAt = issuedAt + TOKEN_VALIDITY`, an `expiresAt` that is still after `(now + TOKEN_VALIDITY - REQUEST_COOLDOWN)` means the token was issued after `(now - REQUEST_COOLDOWN)`, i.e. within the cooldown window — this avoids needing `createdAt` (which is DB-populated via JPA auditing, not directly settable/assertable in a plain unit test) while achieving the same effect using the field that's actually mockable.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.auth.service.PasswordResetServiceTest"`
Expected: PASS (8 tests).

- [ ] **Step 7: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/confcms/cms/domain/PasswordResetToken.java src/main/java/org/confcms/cms/repository/PasswordResetTokenRepository.java src/main/java/org/confcms/cms/auth/service/PasswordResetService.java src/test/java/org/confcms/cms/auth/service/PasswordResetServiceTest.java
git commit -m "feat: add PasswordResetService (enumeration-safe, rate-limited, TDD)

requestReset never reveals whether an email matched an account (same
silent no-op either way) and skips sending a second email within a
5-minute cooldown of the most recent still-valid token. resetPassword
ensures a UserIdentity(provider=local) row exists afterward, so the
same flow doubles as 'set a password for the first time' for an
OAuth-only user, not just recovery."
```

---

### Task 4: Password-reset controller + templates

**Files:**
- Create: `src/main/java/org/confcms/cms/web/controller/PasswordResetController.java`
- Create: `src/main/resources/templates/auth/forgot_password.html`
- Create: `src/main/resources/templates/auth/reset_password.html`
- Modify: `src/main/java/org/confcms/cms/config/SecurityConfig.java` (permit the new routes)
- Modify: `src/main/java/org/confcms/cms/config/DevSecurityConfig.java` (same)
- Modify: `src/main/resources/templates/login.html` (add a "Forgot password?" link)

- [ ] **Step 1: Create the controller**

Create `src/main/java/org/confcms/cms/web/controller/PasswordResetController.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.auth.service.PasswordResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/auth")
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    @GetMapping("/forgot-password")
    public String forgotPasswordForm() {
        return "auth/forgot_password";
    }

    @PostMapping("/forgot-password")
    public String forgotPasswordSubmit(@RequestParam String email, Model model) {
        passwordResetService.requestReset(email);
        model.addAttribute("submitted", true);
        return "auth/forgot_password";
    }

    @GetMapping("/reset-password")
    public String resetPasswordForm(@RequestParam String token, Model model) {
        try {
            passwordResetService.validateToken(token);
            model.addAttribute("token", token);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "auth/reset_password";
    }

    @PostMapping("/reset-password")
    public String resetPasswordSubmit(@RequestParam String token, @RequestParam String password, Model model) {
        try {
            passwordResetService.resetPassword(token, password);
            return "redirect:/login?resetSuccess=true";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return "auth/reset_password";
        }
    }
}
```

- [ ] **Step 2: Create `forgot_password.html`**

Create `src/main/resources/templates/auth/forgot_password.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8" />
    <title>Forgot Password</title>
</head>
<body>
<h1>Forgot Password</h1>
<div th:if="${submitted}">
    <p>If an account exists for that email address, a reset link has been sent.</p>
</div>
<form th:unless="${submitted}" method="post" action="/auth/forgot-password">
    <label for="email">Email</label>
    <input type="email" id="email" name="email" required="required" />
    <button type="submit">Send Reset Link</button>
</form>
<p><a th:href="@{/login}">Back to login</a></p>
</body>
</html>
```

- [ ] **Step 3: Create `reset_password.html`**

Create `src/main/resources/templates/auth/reset_password.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8" />
    <title>Reset Password</title>
</head>
<body>
<h1>Reset Password</h1>
<div th:if="${error}">
    <p th:text="${error}"></p>
    <p><a th:href="@{/auth/forgot-password}">Request a new reset link</a></p>
</div>
<form th:if="${token}" method="post" action="/auth/reset-password">
    <input type="hidden" name="token" th:value="${token}" />
    <label for="password">New Password</label>
    <input type="password" id="password" name="password" required="required" minlength="8" />
    <button type="submit">Reset Password</button>
</form>
</body>
</html>
```

- [ ] **Step 4: Permit the new routes in both security configs**

In `src/main/java/org/confcms/cms/config/SecurityConfig.java`, find the `.requestMatchers("/", "/home", ...).permitAll()` line (already includes `/auth/**` per the existing `AuthRestController` routes — verify `/auth/**` is already in that permitAll list; if so, no change needed here since `/auth/forgot-password` and `/auth/reset-password` are already covered). Read the file to confirm before assuming — if `/auth/**` is not already present, add it to the same `permitAll()` matcher list.

Do the same check for `src/main/java/org/confcms/cms/config/DevSecurityConfig.java`.

- [ ] **Step 5: Add "Forgot password?" link to `login.html`**

Read `src/main/resources/templates/login.html` first to find the form block (around the existing `username`/`password` inputs). Add directly below the submit button:

```html
<p><a th:href="@{/auth/forgot-password}">Forgot password?</a></p>
```

- [ ] **Step 6: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Manual verification via running app**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` in the background, then:
- `curl -s -o /dev/null -w "HTTP_%{http_code}\n" http://localhost:8080/auth/forgot-password` — expect `200`.
- `curl -s -X POST http://localhost:8080/auth/forgot-password -d "email=nobody@nowhere.test" -o /dev/null -w "HTTP_%{http_code}\n"` — expect `200` (same response whether or not the email exists, confirming the enumeration-safe behavior end-to-end, not just at the service layer).
- `curl -s -o /dev/null -w "HTTP_%{http_code}\n" "http://localhost:8080/auth/reset-password?token=does-not-exist"` — expect `200` with the error branch (page still renders 200 with an error message, not a 404/500 — confirm by checking response body contains "invalid" via `curl -s ... | grep -i invalid`).

Stop the app afterward.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/PasswordResetController.java src/main/resources/templates/auth/forgot_password.html src/main/resources/templates/auth/reset_password.html src/main/resources/templates/login.html
git commit -m "feat: add forgot/reset-password pages

Both GET/POST pairs render 200 regardless of whether the token/email
is valid, to keep the underlying enumeration-safe service behavior
observable end-to-end, not just correct at the unit-test layer."
```

---

### Task 5: `MagicLinkAuthenticationProvider` + `MagicLinkAuthenticationToken` (TDD)

**Files:**
- Create: `src/main/java/org/confcms/cms/security/MagicLinkAuthenticationToken.java`
- Create: `src/main/java/org/confcms/cms/security/MagicLinkAuthenticationProvider.java`
- Test: `src/test/java/org/confcms/cms/security/MagicLinkAuthenticationProviderTest.java` (new)

- [ ] **Step 1: Create the token class**

Create `src/main/java/org/confcms/cms/security/MagicLinkAuthenticationToken.java`:

```java
package org.confcms.cms.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public class MagicLinkAuthenticationToken extends AbstractAuthenticationToken {

    private final Object principal;
    private final String magicLinkToken;

    /** Unauthenticated: carries only the raw magic-link token string to be verified. */
    public MagicLinkAuthenticationToken(String magicLinkToken) {
        super(null);
        this.principal = null;
        this.magicLinkToken = magicLinkToken;
        setAuthenticated(false);
    }

    /** Authenticated: carries the resolved principal (email) and granted authorities. */
    public MagicLinkAuthenticationToken(Object principal, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.magicLinkToken = null;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return magicLinkToken;
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }
}
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/org/confcms/cms/security/MagicLinkAuthenticationProviderTest.java`:

```java
package org.confcms.cms.security;

import org.confcms.cms.auth.service.MagicLinkService;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.MagicLink;
import org.confcms.cms.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MagicLinkAuthenticationProviderTest {

    @Mock
    private MagicLinkService magicLinkService;

    private MagicLinkAuthenticationProvider provider;
    private User user;

    @BeforeEach
    void setUp() {
        provider = new MagicLinkAuthenticationProvider(magicLinkService);

        user = new User();
        user.setEmail("author@example.com");
        user.setRole(Role.AUTHOR);
    }

    @Test
    void authenticateRejectsUnknownToken() {
        when(magicLinkService.findByToken("bad-token")).thenReturn(Optional.empty());

        Authentication unauth = new MagicLinkAuthenticationToken("bad-token");

        assertThatThrownBy(() -> provider.authenticate(unauth))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticateRejectsUsedToken() {
        MagicLink link = new MagicLink();
        link.setUser(user);
        link.setExpiresAt(LocalDateTime.now().plusHours(1));
        link.setUsed(true);
        when(magicLinkService.findByToken("used-token")).thenReturn(Optional.of(link));

        Authentication unauth = new MagicLinkAuthenticationToken("used-token");

        assertThatThrownBy(() -> provider.authenticate(unauth))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticateRejectsExpiredToken() {
        MagicLink link = new MagicLink();
        link.setUser(user);
        link.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        link.setUsed(false);
        when(magicLinkService.findByToken("expired-token")).thenReturn(Optional.of(link));

        Authentication unauth = new MagicLinkAuthenticationToken("expired-token");

        assertThatThrownBy(() -> provider.authenticate(unauth))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticateValidTokenMarksUsedAndReturnsAuthenticatedToken() {
        MagicLink link = new MagicLink();
        link.setUser(user);
        link.setExpiresAt(LocalDateTime.now().plusHours(1));
        link.setUsed(false);
        when(magicLinkService.findByToken("good-token")).thenReturn(Optional.of(link));

        Authentication unauth = new MagicLinkAuthenticationToken("good-token");
        Authentication result = provider.authenticate(unauth);

        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isEqualTo("author@example.com");
        assertThat(result.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_AUTHOR");
        verify(magicLinkService).markUsed(link);
    }

    @Test
    void supportsMagicLinkAuthenticationTokenOnly() {
        assertThat(provider.supports(MagicLinkAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.class)).isFalse();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.security.MagicLinkAuthenticationProviderTest"`
Expected: FAILS to compile — `MagicLinkAuthenticationProvider` doesn't exist yet.

- [ ] **Step 4: Implement the provider**

Create `src/main/java/org/confcms/cms/security/MagicLinkAuthenticationProvider.java`:

```java
package org.confcms.cms.security;

import org.confcms.cms.auth.service.MagicLinkService;
import org.confcms.cms.domain.MagicLink;
import org.confcms.cms.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MagicLinkAuthenticationProvider implements AuthenticationProvider {

    private final MagicLinkService magicLinkService;

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        MagicLinkAuthenticationToken token = (MagicLinkAuthenticationToken) authentication;
        String rawToken = (String) token.getCredentials();

        MagicLink link = magicLinkService.findByToken(rawToken)
                .orElseThrow(() -> new BadCredentialsException("This link is invalid, expired, or already used"));

        if (link.isUsed() || link.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BadCredentialsException("This link is invalid, expired, or already used");
        }

        magicLinkService.markUsed(link);

        User user = link.getUser();
        List<GrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));

        return new MagicLinkAuthenticationToken(user.getEmail(), authorities);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return MagicLinkAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.security.MagicLinkAuthenticationProviderTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/security/MagicLinkAuthenticationToken.java src/main/java/org/confcms/cms/security/MagicLinkAuthenticationProvider.java src/test/java/org/confcms/cms/security/MagicLinkAuthenticationProviderTest.java
git commit -m "feat: formalize magic-link verification into a real AuthenticationProvider

Same rejection message for not-found/used/expired (no distinction that
would aid an attacker), same ROLE_* authority format CustomUserDetailsService
produces. Not yet wired into the filter chain -- that's Task 6."
```

---

### Task 6: `MagicLinkAuthenticationFilter`; wire provider + filter into both SecurityConfigs; remove old manual verify code

**Files:**
- Create: `src/main/java/org/confcms/cms/security/MagicLinkAuthenticationFilter.java`
- Modify: `src/main/java/org/confcms/cms/config/SecurityConfig.java`
- Modify: `src/main/java/org/confcms/cms/config/DevSecurityConfig.java`
- Modify: `src/main/java/org/confcms/cms/web/controller/AuthRestController.java` (remove `verifyMagicLink`)
- Test: no new test file — this task is wiring/deletion; the provider's behavior is already covered by Task 5's test, and this codebase has no filter-chain-level test infrastructure (Global Constraint: no `@SpringBootTest`/`MockMvc`) — verified instead via Step 7's manual boot check, consistent with how Task 4 verified its controller manually

- [ ] **Step 1: Create the filter**

Create `src/main/java/org/confcms/cms/security/MagicLinkAuthenticationFilter.java`:

```java
package org.confcms.cms.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class MagicLinkAuthenticationFilter extends OncePerRequestFilter {

    private static final String VERIFY_PATH = "/auth/magic/verify";

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public MagicLinkAuthenticationFilter(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!VERIFY_PATH.equals(request.getServletPath()) && !VERIFY_PATH.equals(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawToken = request.getParameter("token");
        if (rawToken == null || rawToken.isBlank()) {
            response.sendRedirect("/login?error=magic-link-missing-token");
            return;
        }

        try {
            Authentication result = authenticationManager.authenticate(new MagicLinkAuthenticationToken(rawToken));

            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(result);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            response.sendRedirect("/dashboard");
        } catch (BadCredentialsException e) {
            response.sendRedirect("/login?error=magic-link-invalid");
        }
    }
}
```

Note: this explicitly persists the context via `HttpSessionSecurityContextRepository.saveContext(...)` — the fix for the gap identified in the design spec (the old controller code set `SecurityContextHolder` but never persisted it into the session).

- [ ] **Step 2: Wire the provider and filter into `SecurityConfig`**

Read `src/main/java/org/confcms/cms/config/SecurityConfig.java` in full first. Add the import for `MagicLinkAuthenticationFilter`, `MagicLinkAuthenticationProvider`, and `UsernamePasswordAuthenticationFilter` (for `addFilterBefore` positioning). Inject `MagicLinkAuthenticationProvider` via constructor (it's already `@RequiredArgsConstructor` — add a field `private final MagicLinkAuthenticationProvider magicLinkAuthenticationProvider;`).

In the `securityFilterChain` method, after the existing `.authorizeHttpRequests(...)` block and before `.formLogin(...)`, add:

```java
            .authenticationProvider(magicLinkAuthenticationProvider)
            .addFilterBefore(new MagicLinkAuthenticationFilter(authenticationManager(http.getSharedObject(org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration.class))), UsernamePasswordAuthenticationFilter.class)
```

**This exact `authenticationManager(...)` call inside the filter chain builder is fragile** — `AuthenticationConfiguration` may not be reliably available via `getSharedObject` at this point depending on bean initialization order. During implementation, if this doesn't compile or throws at startup, use the alternative: inject `AuthenticationConfiguration authenticationConfiguration` as a constructor field on `SecurityConfig` instead (same as the existing `authenticationManager(AuthenticationConfiguration config)` bean method already does at lines 60-63), and call `new MagicLinkAuthenticationFilter(authenticationConfiguration.getAuthenticationManager())` — this is the safer, more standard pattern and should be preferred if there's any doubt; try it first rather than the `getSharedObject` approach if that seems simpler when actually writing this code.

- [ ] **Step 3: Apply the identical change to `DevSecurityConfig`**

Same edits as Step 2, applied to `src/main/java/org/confcms/cms/config/DevSecurityConfig.java`.

- [ ] **Step 4: Remove the old manual verify endpoint from `AuthRestController`**

Read `src/main/java/org/confcms/cms/web/controller/AuthRestController.java` in full. Delete the entire `verifyMagicLink` method (the `GET /auth/magic/verify` handler that manually builds a `UsernamePasswordAuthenticationToken` and sets `SecurityContextHolder` directly). Remove any now-unused imports this deletion leaves behind (e.g. `UsernamePasswordAuthenticationToken`, `SecurityContextHolder`, `SimpleGrantedAuthority` — check whether `register`/`requestMagicLink` still use any of these; if not, remove the imports).

Leave `requestMagicLink` (`POST /auth/magic/request`) completely untouched — per the design spec, only the verify path moves into the filter chain.

- [ ] **Step 5: Run the full existing test suite**

Run: `./gradlew test`
Expected: all tests pass. If any existing test directly tests `AuthRestController.verifyMagicLink`, it will fail to compile after Step 4's deletion — find and remove that specific test method (grep `verifyMagicLink` in `src/test/` first to check before running, so this is anticipated rather than a surprise).

Run: `grep -rn "verifyMagicLink" src/test/` — if this finds a test method, remove it from whichever `AuthRestControllerTest` file it's in (delete only that method, not the whole file) before running the suite.

- [ ] **Step 6: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Manual verification via running app**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` in the background, then:
- `curl -s -o /dev/null -w "HTTP_%{http_code}\n" "http://localhost:8080/auth/magic/verify?token=nonexistent"` — expect a `302` redirect (to `/login?error=magic-link-invalid`), confirming the filter is intercepting this path (not falling through to a 404, which would mean the filter isn't wired correctly).
- Check the response `Location` header specifically: `curl -sI "http://localhost:8080/auth/magic/verify?token=nonexistent" | grep -i location` — expect it to contain `magic-link-invalid`.

Stop the app afterward.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/confcms/cms/security/MagicLinkAuthenticationFilter.java src/main/java/org/confcms/cms/config/SecurityConfig.java src/main/java/org/confcms/cms/config/DevSecurityConfig.java src/main/java/org/confcms/cms/web/controller/AuthRestController.java
git commit -m "feat: wire magic-link verification into the security filter chain

Replaces the old REST-controller-manual-SecurityContextHolder approach
(which never persisted into the HTTP session) with a real
OncePerRequestFilter + AuthenticationProvider pair, explicitly saving
the context via HttpSessionSecurityContextRepository so the login
actually survives past the single verify request."
```

---

### Task 7: `CustomOAuth2UserService` (TDD) — shared Google/ORCID account resolution logic

**Files:**
- Create: `src/main/java/org/confcms/cms/security/CustomOAuth2UserService.java`
- Test: `src/test/java/org/confcms/cms/security/CustomOAuth2UserServiceTest.java` (new)

This is the core account-linking logic (auto-link by verified email, auto-create as AUTHOR, ORCID `orcidId` population, unverified-email rejection) — written and tested against `OAuth2UserRequest`/`OAuth2User` directly, independent of the actual HTTP OAuth2 redirect dance (which Task 8 wires up and which cannot be unit-tested in this codebase's style per the Global Constraints).

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/confcms/cms/security/CustomOAuth2UserServiceTest.java`:

```java
package org.confcms.cms.security;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.domain.UserIdentity;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomOAuth2UserServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserIdentityRepository userIdentityRepository;

    private CustomOAuth2UserService service;

    @BeforeEach
    void setUp() {
        service = new CustomOAuth2UserService(userRepository, userIdentityRepository);
    }

    private ClientRegistration googleRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("test-client")
                .clientSecret("test-secret")
                .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .clientName("Google")
                .build();
    }

    @Test
    void resolveRejectsUnverifiedEmail() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", "google-sub-123");
        attrs.put("email", "author@example.com");
        attrs.put("email_verified", false);

        assertThatThrownBy(() -> service.resolveLocalUser("google", attrs))
                .isInstanceOf(org.springframework.security.oauth2.core.OAuth2AuthenticationException.class);

        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void resolveAutoLinksToExistingUserByVerifiedEmail() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", "google-sub-123");
        attrs.put("email", "author@example.com");
        attrs.put("email_verified", true);

        User existing = new User();
        existing.setId(1L);
        existing.setEmail("author@example.com");
        existing.setRole(Role.AUTHOR);

        when(userIdentityRepository.findByProviderAndProviderUserId("google", "google-sub-123")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(existing));
        when(userIdentityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.resolveLocalUser("google", attrs);

        assertThat(result).isEqualTo(existing);
        verify(userRepository, never()).save(any());

        ArgumentCaptor<UserIdentity> captor = ArgumentCaptor.forClass(UserIdentity.class);
        verify(userIdentityRepository).save(captor.capture());
        assertThat(captor.getValue().getProvider()).isEqualTo("google");
        assertThat(captor.getValue().getProviderUserId()).isEqualTo("google-sub-123");
        assertThat(captor.getValue().getUser()).isEqualTo(existing);
    }

    @Test
    void resolveAutoCreatesNewUserAsAuthorWhenNoMatch() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", "google-sub-999");
        attrs.put("email", "newperson@example.com");
        attrs.put("email_verified", true);
        attrs.put("name", "New Person");

        when(userIdentityRepository.findByProviderAndProviderUserId("google", "google-sub-999")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("newperson@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userIdentityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.resolveLocalUser("google", attrs);

        assertThat(result.getRole()).isEqualTo(Role.AUTHOR);
        assertThat(result.getEmail()).isEqualTo("newperson@example.com");
        assertThat(result.getPasswordHash()).isNull();
        verify(userRepository).save(any());
    }

    @Test
    void resolvePopulatesOrcidIdOnlyForOrcidProvider() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("orcid-identifier", "0000-0002-1825-0097");
        attrs.put("email", "researcher@example.com");
        attrs.put("email_verified", true);

        when(userIdentityRepository.findByProviderAndProviderUserId("orcid", "0000-0002-1825-0097")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("researcher@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userIdentityRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = service.resolveLocalUser("orcid", attrs);

        assertThat(result.getOrcidId()).isEqualTo("0000-0002-1825-0097");
    }

    @Test
    void resolveShortCircuitsOnRepeatLoginByExistingIdentity() {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", "google-sub-123");
        attrs.put("email", "author@example.com");
        attrs.put("email_verified", true);

        User existing = new User();
        existing.setId(1L);
        existing.setEmail("author@example.com");
        existing.setRole(Role.AUTHOR);

        UserIdentity existingIdentity = new UserIdentity();
        existingIdentity.setUser(existing);
        existingIdentity.setProvider("google");
        existingIdentity.setProviderUserId("google-sub-123");

        when(userIdentityRepository.findByProviderAndProviderUserId("google", "google-sub-123")).thenReturn(Optional.of(existingIdentity));

        User result = service.resolveLocalUser("google", attrs);

        assertThat(result).isEqualTo(existing);
        verify(userRepository, never()).findByEmail(any());
        verify(userIdentityRepository, never()).save(any());
    }
}
```

Note: this test file targets a package-visible `resolveLocalUser(String provider, Map<String, Object> attributes)` method rather than mocking the full `OAuth2UserRequest`/`loadUser` chain — Step 2 below designs `CustomOAuth2UserService` with that method as its real, directly-testable core, with `loadUser` as a thin adapter around it (extracting `attributes` from the real `OAuth2User`/`OidcUser` Spring returns, then delegating). This keeps the provider-agnostic account-resolution logic fully unit-testable without needing to construct real `OAuth2UserRequest` HTTP-calling objects.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.security.CustomOAuth2UserServiceTest"`
Expected: FAILS to compile — `CustomOAuth2UserService` doesn't exist yet.

- [ ] **Step 3: Implement `CustomOAuth2UserService`**

Create `src/main/java/org/confcms/cms/security/CustomOAuth2UserService.java`:

```java
package org.confcms.cms.security;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.domain.UserIdentity;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oauth2User = super.loadUser(userRequest);
        String provider = userRequest.getClientRegistration().getRegistrationId();

        User localUser = resolveLocalUser(provider, oauth2User.getAttributes());

        GrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + localUser.getRole().name());
        return new DefaultOAuth2User(Collections.singletonList(authority), oauth2User.getAttributes(), emailAttributeKey(provider));
    }

    @Transactional
    public User resolveLocalUser(String provider, Map<String, Object> attributes) {
        Boolean verified = (Boolean) attributes.get("email_verified");
        if (verified == null || !verified) {
            throw new OAuth2AuthenticationException(new OAuth2Error("email_not_verified"),
                    "Provider did not return a verified email address");
        }

        String providerUserId = providerUserId(provider, attributes);
        String email = (String) attributes.get("email");

        Optional<UserIdentity> existingIdentity = userIdentityRepository.findByProviderAndProviderUserId(provider, providerUserId);
        if (existingIdentity.isPresent()) {
            return existingIdentity.get().getUser();
        }

        User user = userRepository.findByEmail(email).orElseGet(() -> {
            User created = new User();
            created.setEmail(email);
            created.setFullName((String) attributes.getOrDefault("name", email));
            created.setRole(Role.AUTHOR);
            created.setEnabled(true);
            return userRepository.save(created);
        });

        if ("orcid".equals(provider)) {
            user.setOrcidId(providerUserId);
            userRepository.save(user);
        }

        UserIdentity identity = new UserIdentity();
        identity.setUser(user);
        identity.setProvider(provider);
        identity.setProviderUserId(providerUserId);
        identity.setLinkedAt(LocalDateTime.now());
        userIdentityRepository.save(identity);

        return user;
    }

    private String providerUserId(String provider, Map<String, Object> attributes) {
        if ("orcid".equals(provider)) {
            return (String) attributes.get("orcid-identifier");
        }
        return (String) attributes.get("sub");
    }

    private String emailAttributeKey(String provider) {
        return "email";
    }
}
```

**Note on `email_verified` for ORCID**: ORCID's `/v3.0/{orcid}/person` endpoint may not return an `email_verified` boolean in the same shape Google does — confirm ORCID's actual response shape during Task 8 (when wiring the real provider config) and adjust `resolveLocalUser`'s verification check if needed; this task's tests use a literal `email_verified` boolean key for both providers as a reasonable placeholder consistent with the design spec's acknowledgment that exact ORCID attribute paths are "confirmed during implementation." If ORCID doesn't expose a verified-email concept at all (some identity providers don't), the fallback decided here should be documented as a code comment directly in `providerUserId`/verification logic, and Task 8's manual smoke test is where this gets caught for real.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.security.CustomOAuth2UserServiceTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/confcms/cms/security/CustomOAuth2UserService.java src/test/java/org/confcms/cms/security/CustomOAuth2UserServiceTest.java
git commit -m "feat: add CustomOAuth2UserService (shared Google/ORCID account resolution, TDD)

One provider-agnostic implementation: rejects unverified emails,
short-circuits on repeat login via existing UserIdentity, auto-links
to an existing User by verified email, auto-creates as AUTHOR when no
match, populates User.orcidId only for the orcid provider.
resolveLocalUser is the directly-testable core; loadUser is a thin
adapter extracting attributes from the real OAuth2User Spring Security
constructs from the live HTTP round trip (verified manually in the
next task, consistent with this codebase having no MockMvc/SpringBootTest
infrastructure for that kind of test)."
```

---

### Task 8: Wire `oauth2Login()` into both SecurityConfigs; per-provider `@ConditionalOnProperty` registration beans; `login.html` conditional buttons

**Files:**
- Create: `src/main/java/org/confcms/cms/config/OAuth2ProviderConfig.java`
- Modify: `src/main/java/org/confcms/cms/config/SecurityConfig.java`
- Modify: `src/main/java/org/confcms/cms/config/DevSecurityConfig.java`
- Modify: `src/main/resources/application.properties` (uncomment/complete Google block, add ORCID block)
- Modify: `src/main/resources/templates/login.html` (replace dead commented-out OAuth markup with real `th:if` buttons)

- [ ] **Step 1: Create the conditional `ClientRegistrationRepository` config**

Create `src/main/java/org/confcms/cms/config/OAuth2ProviderConfig.java`:

```java
package org.confcms.cms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class OAuth2ProviderConfig {

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${spring.security.oauth2.client.registration.google.client-id:}") String googleClientId,
            @Value("${spring.security.oauth2.client.registration.google.client-secret:}") String googleClientSecret,
            @Value("${spring.security.oauth2.client.registration.orcid.client-id:}") String orcidClientId,
            @Value("${spring.security.oauth2.client.registration.orcid.client-secret:}") String orcidClientSecret) {

        List<ClientRegistration> registrations = new ArrayList<>();

        if (!googleClientId.isBlank() && !googleClientSecret.isBlank()) {
            registrations.add(ClientRegistration.withRegistrationId("google")
                    .clientId(googleClientId)
                    .clientSecret(googleClientSecret)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("openid", "profile", "email")
                    .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                    .tokenUri("https://www.googleapis.com/oauth2/v4/token")
                    .userInfoUri("https://www.googleapis.com/oauth2/v3/userinfo")
                    .userNameAttributeName("sub")
                    .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                    .clientName("Google")
                    .build());
        }

        if (!orcidClientId.isBlank() && !orcidClientSecret.isBlank()) {
            registrations.add(ClientRegistration.withRegistrationId("orcid")
                    .clientId(orcidClientId)
                    .clientSecret(orcidClientSecret)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .scope("/authenticate")
                    .authorizationUri("https://orcid.org/oauth/authorize")
                    .tokenUri("https://orcid.org/oauth/token")
                    .userInfoUri("https://pub.orcid.org/v3.0/{orcid}/person")
                    .userNameAttributeName("orcid-identifier")
                    .clientName("ORCID")
                    .build());
        }

        return new InMemoryClientRegistrationRepository(registrations);
    }
}
```

**Note**: an empty `registrations` list (no providers configured anywhere) passed to `InMemoryClientRegistrationRepository` — confirm during implementation whether this throws at construction (some Spring Security versions require at least one registration) or is safely empty; if it throws, the fix is a guard that skips registering `oauth2Login()` in the security filter chain entirely when the repository would be empty (check `registrations.isEmpty()` before calling `.oauth2Login(...)` in Step 2/3 below), rather than trying to force a placeholder registration into existence.

**Note on ORCID's userInfoUri templating**: `{orcid}` in the URI is a path placeholder ORCID's API expects to be substituted with the authenticated user's own ORCID iD, which isn't known until after the token exchange — standard `DefaultOAuth2UserService`/`ClientRegistration` handles fixed `userInfoUri` values, not templated ones requiring a value from the token response. This is flagged in the design spec as "confirmed against ORCID's docs during implementation" — if the literal templated-URI approach shown above doesn't work with Spring's default `userInfoUri` handling (likely, since ORCID's OAuth2 token response actually includes the `orcid` field directly in the token response body, not requiring a separate userinfo call with a path substitution), the implementer should instead extract `orcid-identifier` directly from the token response's additional parameters (accessible via a custom `OAuth2AccessTokenResponseClient` or by reading `OAuth2AccessTokenResponse.getAdditionalParameters()`) rather than a second HTTP call — this is a real ORCID-API-shape detail to verify against ORCID's actual current documentation at implementation time, not something to guess further here.

- [ ] **Step 2: Wire `oauth2Login()` into `SecurityConfig`**

Read the file in full first. Add `CustomOAuth2UserService` as a constructor-injected field. In the filter chain builder, after the magic-link wiring from Task 6, add:

```java
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
            )
```

- [ ] **Step 3: Apply the identical change to `DevSecurityConfig`**

Same edit, applied to `DevSecurityConfig.java`.

- [ ] **Step 4: Complete the Google properties block, add ORCID properties block**

Read `application.properties` around the existing commented-out Google/Azure block (identified during planning-time research). Replace the Google block with:

```properties
# OAuth2 - Google (Disabled by default - set both properties to enable)
#spring.security.oauth2.client.registration.google.client-id=${GOOGLE_CLIENT_ID}
#spring.security.oauth2.client.registration.google.client-secret=${GOOGLE_CLIENT_SECRET}

# OAuth2 - ORCID (Disabled by default - set both properties to enable)
#spring.security.oauth2.client.registration.orcid.client-id=${ORCID_CLIENT_ID}
#spring.security.oauth2.client.registration.orcid.client-secret=${ORCID_CLIENT_SECRET}
```

(The Azure block from the original file may be left as-is or removed — it's unrelated to this feature and was never a requirement; leave it untouched to minimize unrelated diff.)

- [ ] **Step 5: Replace dead OAuth markup in `login.html` with real conditional buttons**

Read `login.html` in full first, specifically the commented-out static OAuth block identified during planning-time research. Replace that entire commented-out block with:

```html
<div th:if="${googleEnabled} or ${orcidEnabled}">
    <p>Or login with:</p>
    <a th:if="${googleEnabled}" th:href="@{/oauth2/authorization/google}">Google</a>
    <a th:if="${orcidEnabled}" th:href="@{/oauth2/authorization/orcid}">ORCID</a>
</div>
```

This requires `googleEnabled`/`orcidEnabled` model attributes — find whichever controller currently renders `/login` (check `WebConfig.java`'s view-controller registration from planning-time research; it's likely a plain `registerViewController("/login")` with no backing `@Controller` method, meaning no `Model` is available to populate). If `/login` has no real controller method (just a view-controller mapping), add a minimal one: a small new `@Controller` method (or extend `PublicWebController` if that's confirmed to be where `/login` should logically live — check for an existing `PublicWebController` or similar during this step) that injects `ClientRegistrationRepository` and adds:

```java
model.addAttribute("googleEnabled", clientRegistrationRepository.findByRegistrationId("google") != null);
model.addAttribute("orcidEnabled", clientRegistrationRepository.findByRegistrationId("orcid") != null);
```

replacing the plain view-controller registration for `/login` in `WebConfig.java` with this new controller method (remove the `/login` line from `WebConfig`'s `addViewControllers` if this step adds a real controller for it, to avoid a duplicate-mapping conflict).

- [ ] **Step 6: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run the full existing test suite**

Run: `./gradlew test`
Expected: all tests pass.

- [ ] **Step 8: Manual verification via running app (no real OAuth2 credentials needed for this check)**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` in the background, then:
- `curl -s http://localhost:8080/login | grep -i "google\|orcid"` — expect **no** matches, since no `client-id`/`client-secret` properties are set in this dev environment by default, confirming the conditional hiding works (an installation with no OAuth2 credentials configured shows neither button).
- `curl -s -o /dev/null -w "HTTP_%{http_code}\n" http://localhost:8080/oauth2/authorization/google` — expect `404` (no registration exists), confirming disabled providers aren't reachable even by direct URL.

Stop the app afterward. (Full end-to-end OAuth2 redirect testing with real Google/ORCID credentials is a manual, human-driven verification step outside this plan's automated scope — note this to the user after this task completes, since it requires real registered OAuth2 apps with valid redirect URIs, which can't be set up unattended.)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/confcms/cms/config/OAuth2ProviderConfig.java src/main/java/org/confcms/cms/config/SecurityConfig.java src/main/java/org/confcms/cms/config/DevSecurityConfig.java src/main/resources/application.properties src/main/resources/templates/login.html
git commit -m "feat: wire Google + ORCID OAuth2 login, per-installation enable/disable

Each provider's ClientRegistration only exists when its client-id/
client-secret properties are set -- an installation with neither
configured shows no OAuth buttons and /oauth2/authorization/<provider>
404s naturally via Spring Security's own registration lookup, no
custom guard code needed for the disabled case."
```

(If Step 5 required adding a new controller for `/login`, include that file in this commit too, and update the `git add` line accordingly when actually executing this task.)

---

### Task 9: `AccountSettingsController` + `account_settings.html` — connected-accounts UI

**Files:**
- Create: `src/main/java/org/confcms/cms/web/controller/AccountSettingsController.java`
- Create: `src/main/resources/templates/account_settings.html`
- Test: `src/test/java/org/confcms/cms/web/controller/AccountSettingsControllerTest.java` (new)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/confcms/cms/web/controller/AccountSettingsControllerTest.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.domain.UserIdentity;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountSettingsControllerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private UserIdentityRepository userIdentityRepository;

    private AccountSettingsController controller;

    @BeforeEach
    void setUp() {
        controller = new AccountSettingsController(userRepository, userIdentityRepository);
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, Collections.emptyList()));
    }

    @Test
    void showListsLinkedIdentitiesForCurrentUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail("author@example.com");
        user.setRole(Role.AUTHOR);
        authenticateAs(user);

        UserIdentity local = new UserIdentity();
        local.setProvider("local");
        UserIdentity google = new UserIdentity();
        google.setProvider("google");

        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));
        when(userIdentityRepository.findByUserId(1L)).thenReturn(List.of(local, google));

        Model model = new ExtendedModelMap();
        String view = controller.show(model);

        assertThat(view).isEqualTo("account_settings");
        assertThat(model.getAttribute("identities")).isEqualTo(List.of(local, google));
        assertThat(model.getAttribute("user")).isEqualTo(user);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.AccountSettingsControllerTest"`
Expected: FAILS to compile — `AccountSettingsController` doesn't exist yet.

- [ ] **Step 3: Implement the controller**

Create `src/main/java/org/confcms/cms/web/controller/AccountSettingsController.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserIdentityRepository;
import org.confcms.cms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class AccountSettingsController {

    private final UserRepository userRepository;
    private final UserIdentityRepository userIdentityRepository;

    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }

    @GetMapping("/account")
    public String show(Model model) {
        User user = actingUser();
        model.addAttribute("user", user);
        model.addAttribute("identities", userIdentityRepository.findByUserId(user.getId()));
        return "account_settings";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.AccountSettingsControllerTest"`
Expected: PASS (1 test).

- [ ] **Step 5: Create the template**

Create `src/main/resources/templates/account_settings.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8" />
    <title>Account Settings</title>
</head>
<body>
<h1>Account Settings</h1>
<p>Signed in as <span th:text="${user.email}"></span></p>

<h2>Connected Accounts</h2>
<ul>
    <li th:each="identity : ${identities}">
        <span th:text="${identity.provider}"></span>
        <span th:if="${identity.linkedAt}" th:text="'linked ' + ${identity.linkedAt}"></span>
    </li>
</ul>

<p><a th:href="@{/oauth2/authorization/google}">Connect Google</a></p>
<p><a th:href="@{/oauth2/authorization/orcid}">Connect ORCID</a></p>
<p><a th:href="@{/auth/forgot-password}">Set / change password</a></p>
</body>
</html>
```

(Connecting Google/ORCID while already authenticated re-enters the normal `oauth2Login` flow from Task 8 — `CustomOAuth2UserService.resolveLocalUser` matches by the current session's email and adds the identity, no special-casing needed, per the design spec. "Set/change password" reuses the existing forgot-password flow from Task 4 rather than a separate authenticated-only form, per the design spec's explicit choice to keep one code path for both cases.)

- [ ] **Step 6: Add `/account` to permitted authenticated routes**

Read `SecurityConfig.java`/`DevSecurityConfig.java` again — `/account` should fall under `.anyRequest().authenticated()` already (it's not `/admin/**`, `/review/**`, or `/submission/**`), so no explicit matcher change should be needed. Confirm this is actually true by checking the matcher list doesn't have some other catch-all that would block it, rather than assuming.

- [ ] **Step 7: Verify full compilation and full test suite**

Run: `./gradlew compileJava test`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 8: Manual verification via running app**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` in the background, then:
- `curl -s -o /dev/null -w "HTTP_%{http_code}\n" http://localhost:8080/account` — expect `302` (redirect to login, unauthenticated), confirming the route is wired and gated, not a 404.

Stop the app afterward.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/AccountSettingsController.java src/main/resources/templates/account_settings.html src/test/java/org/confcms/cms/web/controller/AccountSettingsControllerTest.java
git commit -m "feat: add account settings page listing connected login methods

Connect-Google/Connect-ORCID links re-enter the normal oauth2Login
flow while authenticated; CustomOAuth2UserService's existing
email-matching logic adds the identity to the current user with no
special-casing needed. Set/change-password reuses the forgot-password
flow rather than a separate authenticated-only form."
```

---

### Task 10: Final full-suite verification

**Files:** none changed — verification only.

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew clean test`
Expected: BUILD SUCCESSFUL, all tests pass across every test class from this plan and every prior plan on `main`.

- [ ] **Step 2: Run a full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Start the app and smoke-test the full set of new routes**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` in the background.

- `GET /login` → 200 (regression check).
- `GET /auth/forgot-password` → 200.
- `GET /auth/reset-password?token=x` → 200 (error branch).
- `GET /auth/magic/verify?token=x` → 302 to `/login?error=magic-link-invalid`.
- `GET /account` → 302 (unauthenticated redirect).
- `GET /admin/decisions/ui` → 302 (regression check against the prior session's `/admin/**` authorization fix — still gated, still not 404).
- `GET /committee` → 200 (regression check).

Stop the app afterward. No commit needed for this verification-only task.

- [ ] **Step 4: Report the manual-verification gap to the user**

This plan's automated checks cannot exercise a real Google/ORCID OAuth2 authorization-code round trip (requires real registered OAuth2 applications with valid redirect URIs pointed at a reachable host, which can't be provisioned unattended). After this task, explicitly tell the user: the code is wired and unit-tested, but actually logging in via Google/ORCID needs to be manually verified once real client-id/client-secret values are configured for a real or sandbox OAuth2 app on each provider.

---

## Self-Review Notes (for whoever executes this plan)

- **Spec coverage**: every section of `docs/superpowers/specs/2026-09-21-multi-auth-design.md` maps to a task — §2 (data model) → Tasks 1-3, §3.2 (magic link) → Tasks 5-6, §3.3 (OAuth2) → Tasks 7-8, §4 (password reset) → Tasks 3-4, §5 (account linking UI) → Task 9, §6 (error handling) is threaded through each task's tests rather than a standalone task, §7 (testing approach) is the Global Constraint every task follows.
- **`UserRepository.findByProviderAndProviderId` removal**: this was NOT mentioned in the design spec itself — found only during plan-writing research (Section 2 exact-signatures pass). Task 2 explicitly handles it, since leaving it in place would be dead code referencing columns Task 2 also removes, and leaving the columns without removing this method would silently pass compilation while being logically inconsistent with the spec's stated intent to drop them.
- **The `MagicLinkAuthenticationFilter`'s `authenticationManager(...)` wiring (Task 6, Step 2) is flagged as potentially fragile** — this is a genuine implementation-time judgment call between two valid approaches, not a placeholder; the plan gives the safer fallback explicitly rather than leaving it to guesswork.
- **ORCID's exact API response shape (`email_verified` presence, `userInfoUri` templating with `{orcid}`) is flagged in Tasks 7 and 8 as needing real-documentation confirmation at implementation time** — the design spec itself already flagged this as "confirmed during implementation," and this plan carries that forward explicitly rather than inventing exact values that would just be guesses.
- **Type consistency check**: `UserIdentity.provider`/`providerUserId` (Task 1) are used identically in `CustomOAuth2UserService` (Task 7), `PasswordResetService` (Task 3), and `AccountSettingsController`/template (Task 9) — same field names throughout, verified by re-reading each task's code against Task 1's entity definition.
- **No placeholders**: every step has real code, not "add appropriate handling" — the two explicitly-flagged uncertainties above (filter wiring, ORCID API shape) are flagged with a concrete fallback/investigation instruction, not left vague.
