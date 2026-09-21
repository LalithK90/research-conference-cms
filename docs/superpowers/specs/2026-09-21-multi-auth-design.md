# Multi-Auth (Password Reset, Magic Link, Google & ORCID OAuth2) — Design Spec

## 1. Scope

This feature covers four login/identity methods for a person to authenticate into the system:

- **Password** (existing, unchanged) — plus a new **forgot-password / reset-password** flow (currently absent entirely).
- **Magic link** (existing but ad-hoc — `MagicLinkService`/`AuthRestController` exist and are reachable, but bypass the Spring Security filter chain: `AuthRestController.verifyMagicLink` manually calls `SecurityContextHolder.getContext().setAuthentication(...)` with no `HttpServletRequest`/`SecurityContextRepository` involved, so the authenticated context is not persisted into an HTTP session — under Spring Security's default session-per-request handling this means the login likely does not survive past the single request that set it. This is formalized into a real `AuthenticationProvider` in this feature, fixing the persistence gap as a side effect).
- **Google OAuth2** (dependency present, registration commented out, zero wiring).
- **ORCID OAuth2** (nothing exists today — full new provider registration).

**Explicitly out of scope for this round:** Zenodo as a login method (it remains a future proceedings-deposit integration, item #12 on the project roadmap — a different kind of OAuth2 use, not a login method most users need). Invitation-acceptance flow changes (`PersonInvitationController`/`PersonInvitationService` are untouched — accepting an invitation remains password-only; a user can link Google/ORCID afterward via the new "Connected accounts" page).

## 2. Data model

### 2.1 `UserIdentity` (new entity)

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
    private String providerUserId; // Google's "sub" claim, ORCID's iD; null for "local"

    @Column(nullable = false)
    private LocalDateTime linkedAt;
}
```

One row per linked method, including `"local"` (password) — created alongside `User.passwordHash` being set, so "does this user have a password" and "does this user have Google linked" are answered the same way (query `UserIdentity`), not two different mechanisms. `(provider, provider_user_id)` is unique so a given Google/ORCID account can only ever be linked to one local `User`.

### 2.2 `User` changes

- Add: `private String orcidId;` (nullable, no uniqueness constraint at the DB level beyond what `UserIdentity` already enforces via `(provider, provider_user_id)` — this column is a denormalized read-convenience for future author-disambiguation in proceedings, not a second source of truth).
- **Drop**: `provider` and `providerId` columns (`User.java`, currently unused dead fields — confirmed via repo-wide search, zero reads/writes outside the declaration). Because this project relies on `spring.jpa.hibernate.ddl-auto=update` (prod-ish profile) / `ddl-auto=none` (dev profile) with no Flyway/Liquibase, **`ddl-auto=update` will not drop columns automatically**. The implementation plan will include an explicit `ALTER TABLE users DROP COLUMN provider, DROP COLUMN provider_id;` step documented for whoever runs deployment migrations, and the dev profile's schema/seed SQL (`data-dev.sql` / whatever creates the dev schema) updated directly since dev has no auto-migration at all.

### 2.3 `PasswordResetToken` (new entity, mirrors `PersonInvitation`'s token pattern — not reusing `MagicLink`, same reasoning as why `PersonInvitation` got its own token fields instead of reusing `MagicLink` in an earlier feature)

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

Token generated the same way every other token in this codebase is generated: `UUID.randomUUID().toString()` inline at the issuance call site (matches `PersonInvitationService` and `MagicLinkService` precedent — no new token utility needed).

## 3. Authentication flow

### 3.1 Password login

Unchanged. `CustomUserDetailsService` continues to work as-is.

### 3.2 Magic link (formalized into the security filter chain)

- `MagicLinkAuthenticationToken extends AbstractAuthenticationToken` — unauthenticated variant carries just the token string; authenticated variant wraps the resolved `User`'s principal/authorities.
- `MagicLinkAuthenticationFilter extends OncePerRequestFilter` (or `AbstractAuthenticationProcessingFilter`, decided during planning based on which integrates more cleanly with this project's existing filter chain shape) — intercepts `GET /auth/magic/verify?token=...`, builds the unauthenticated token, delegates to `AuthenticationManager.authenticate(...)`.
- `MagicLinkAuthenticationProvider implements AuthenticationProvider` — looks up `MagicLink` by token via `MagicLinkService.findByToken`, rejects if absent/used/expired (same rejection messages as today: generic, no distinction that would help an attacker), calls `MagicLinkService.markUsed(link)`, loads the `User`, returns a fully-authenticated token with authority `"ROLE_" + user.getRole().name()` (matching `CustomUserDetailsService`'s exact format).
- On success, the normal Spring Security filter chain persists the `SecurityContext` into the `HttpSession` via `SecurityContextRepository` (the default `HttpSessionSecurityContextRepository` already implicitly in play for form login) — this is the actual fix for the "doesn't survive past one request" gap identified in Section 1.
- `AuthRestController.requestMagicLink` (`POST /auth/magic/request`) is **unchanged** — it stays a REST endpoint that sends the email; only `verifyMagicLink`'s logic moves into the filter chain, and the old manual `SecurityContextHolder.set()` code is deleted from the controller (the controller method itself may be deleted entirely if `MagicLinkAuthenticationFilter` fully replaces its route, decided during planning).

### 3.3 Google / ORCID OAuth2 login

- `oauth2Login()` added to both `SecurityConfig` and `DevSecurityConfig`, backed by a single shared `CustomOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User>` (provider-agnostic — one implementation handles both Google and ORCID, since the only per-provider difference is which attribute holds the email and which holds the subject id, both read from `OAuth2UserRequest.getClientRegistration().getRegistrationId()`).
- On successful provider authentication:
  1. Extract verified email + provider subject id from returned attributes. **If the provider's email is not marked verified, reject the login** (clear error, no account created/linked) — this is the actual security-sensitive edge case the whole auto-link design depends on.
  2. Look up `User` by email.
     - **Found** → auto-link: if no `UserIdentity(provider, providerUserId)` row exists yet for this user, create one; log them in as this existing user. (Repeat logins: `UserIdentity` is looked up directly by `(provider, providerUserId)` first, before falling back to email lookup — so a returning OAuth2 user never re-triggers the "existing account?" branch at all.)
     - **Not found** → auto-create a new `User` (`role = AUTHOR`, `passwordHash = null`), create the `UserIdentity` row.
  3. If provider is `"orcid"`, set/update `User.orcidId` from the returned iD (denormalized per Section 2.2).
  4. Return an `OAuth2User`/`OidcUser` implementation wrapping the local `User`'s authorities in the same `"ROLE_" + role.name()` format used everywhere else.
- **Per-installation enable/disable**: each provider's `ClientRegistration` bean is created only when its `client-id`/`client-secret` properties are present, via `@ConditionalOnProperty(prefix = "spring.security.oauth2.client.registration.google", name = "client-id")` (and the ORCID equivalent) on small per-provider `@Configuration` classes. `login.html`'s already-stubbed-but-dead OAuth section (currently a commented-out static HTML block) is replaced with real `th:if` conditionals driven by which registrations are present in the injected `ClientRegistrationRepository` — an installation with no ORCID credentials configured simply doesn't render that button. Accessing a disabled provider's URL directly (e.g. `/oauth2/authorization/orcid` with no registration configured) is naturally rejected by Spring Security itself (no registration found in the repository) — no extra code needed.
- ORCID-specific registration properties (added to `application.properties`, commented out by default, following the exact existing Google/Azure pattern):
  ```properties
  # OAuth2 - ORCID (Disabled by default - configure to enable)
  #spring.security.oauth2.client.registration.orcid.client-id=${ORCID_CLIENT_ID}
  #spring.security.oauth2.client.registration.orcid.client-secret=${ORCID_CLIENT_SECRET}
  #spring.security.oauth2.client.registration.orcid.scope=/authenticate
  #spring.security.oauth2.client.registration.orcid.authorization-grant-type=authorization_code
  #spring.security.oauth2.client.registration.orcid.redirect-uri={baseUrl}/login/oauth2/code/orcid
  #spring.security.oauth2.client.provider.orcid.authorization-uri=https://orcid.org/oauth/authorize
  #spring.security.oauth2.client.provider.orcid.token-uri=https://orcid.org/oauth/token
  #spring.security.oauth2.client.provider.orcid.user-info-uri=https://pub.orcid.org/v3.0/{orcid}/person
  #spring.security.oauth2.client.provider.orcid.user-name-attribute=orcid-identifier
  ```
  (Exact URIs/attribute paths confirmed against ORCID's published OAuth2 documentation during implementation — placeholder values above establish the property shape, matching the existing Google/Azure commented-out block's own placeholder nature.)
  The existing commented-out Google block already present in `application.properties` is uncommented/completed with real property names (`redirect-uri`, standard Google provider URIs are Spring Security's well-known-provider defaults — Google is one of Spring Security's built-in common providers, so `spring.security.oauth2.client.provider.google.*` URIs don't need to be hand-specified the way ORCID's do).

## 4. Password reset flow

1. `GET /auth/forgot-password` → form (email only).
2. `POST /auth/forgot-password` → look up `User` by email.
   - **Always return the same generic response** ("If that email exists, we've sent a reset link") regardless of match — prevents user enumeration (OWASP-standard mitigation, matches this project's established "best security app" bar).
   - If found: create `PasswordResetToken` (`expiresAt = now + 2 hours`, matching `MagicLink`'s existing expiry window for consistency), queue email via `emailService.sendSimpleEmail(...)` (following the exact pattern `AuthRestController.requestMagicLink` already uses — plain string body, not `sendTemplateEmail`, for consistency with the existing auth-email style; `sendTemplateEmail` exists but no auth code uses it today, and this feature doesn't introduce a first usage without discussion).
   - **Rate limiting**: reject (silently, same generic response) a second request for the same email within a cooldown window (e.g. 5 minutes) — a simple `lastRequestedAt` check against the most recent non-expired token for that user, no new dependency (this codebase has no existing rate-limiting infrastructure; a timestamp comparison is the lazy-correct option here, not a new library like Bucket4j).
3. `GET /auth/reset-password?token=...` → validate (exists, not expired, not used) → render new-password form, or a generic "this link has expired or was already used" error page.
4. `POST /auth/reset-password` → re-validate token, set `User.passwordHash` (bcrypt via the existing `PasswordEncoder` bean), mark token `used=true`, ensure a `UserIdentity(provider="local")` row exists for this user (covers both genuine password recovery AND an OAuth-only user setting a password for the first time — same endpoint serves both cases, no separate "add password" flow needed).
5. Redirect to `/login` with a success message.

## 5. Account-linking UI

New "Connected accounts" section. Confirmed during spec self-review: no profile/settings controller or page exists anywhere in this codebase today (`/dashboard`, the post-login redirect target, is currently handled inside `AdminConferenceController` alongside unrelated admin conference-management routes — not a suitable home for this). This feature therefore adds a new, small `AccountSettingsController` (`GET /account`, plain authenticated route, no role restriction beyond being logged in) and a matching `account_settings.html` template, greenfield. Lists the current user's `UserIdentity` rows (provider name, linked date) and offers:
- "Connect Google" / "Connect ORCID" buttons — re-enters the normal `oauth2Login` flow while already authenticated; the shared `CustomOAuth2UserService` matches by email (the current session's user) and adds the `UserIdentity` row, no special-casing needed beyond what Section 3.3 already describes.
- "Set/change password" — reuses the reset-password form logic from Section 4 step 4, entered directly rather than via a token link (since the user is already authenticated).

`PersonInvitationController` and `PersonInvitationService` are **not modified** by this feature (confirmed scope decision, Section 1).

## 6. Error handling summary

- OAuth2 provider returns an unverified email → reject, no account action (Section 3.3).
- Reused/expired magic-link or password-reset token → generic rejection message, no distinction between "used" and "expired" that would aid an attacker.
- Disabled/unconfigured provider accessed directly → Spring Security's own `ClientRegistrationRepository` lookup fails naturally; no custom handling required.
- Password-reset request for a non-existent email → identical response to a real match (Section 4 step 2).
- Two different provider accounts sharing an email but a provider later changes its subject id (rare account-merge scenario, e.g. ORCID) → explicitly out of scope; treated as a new identity if it occurs, matching how most systems handle this edge case.

## 7. Testing approach

Following this project's established pattern (pure Mockito unit tests, no `@SpringBootTest`/`MockMvc` anywhere in the codebase today):
- `CustomOAuth2UserService`: auto-link vs auto-create branches, unverified-email rejection, ORCID `orcidId` population, repeat-login-by-identity short-circuit.
- `MagicLinkAuthenticationProvider`: valid/expired/used/not-found token cases, correct authority format.
- Password-reset service: token issuance, expiry/used rejection, enumeration-safe response consistency, rate-limit cooldown behavior, `UserIdentity(provider="local")` upsert on reset.
- Manual boot + browser smoke test for the actual OAuth2 authorization-code redirect round trip with real (or sandbox) Google/ORCID app credentials — inherently a real-HTTP-round-trip concern unit tests can't cover, same approach used for every prior feature in this project needing a live boot check.

## 8. Deferred / explicitly out of scope

- Zenodo as a login method (future proceedings-deposit integration, roadmap item #12).
- Invitation-acceptance flow gaining direct OAuth2 support (stays password-only; link afterward via Connected Accounts).
- Handling ORCID/Google account-merge-changes-subject-id edge case.
- A real database migration tool (Flyway/Liquibase) — the `User.provider`/`providerId` column drop is handled as a one-off manual SQL step for this feature rather than introducing new migration infrastructure as a side effect.
