# PDPA / Security Technical Baseline — Design Spec

## 1. Scope

This feature covers four independent fixes, all "technical baseline" per the gap-analysis doc's own scoping (consent capture, retention/deletion workflows, and breach-notification process are explicitly out of scope — future items):

1. **First-run admin credential**: replace the static seeded admin account with a randomly-generated password created on first boot, logged once.
2. **Admin-created passwordless users**: a new, admin-only "create user directly" feature (email + full name + role, no password) — the created user's FIRST login must be via Google or magic link (both auto-link/resolve by email with no password check anywhere in that path, confirmed against current code). ORCID cannot bootstrap a first login for this user (confirmed: ORCID's basic grant supplies no email, so a cold ORCID login is unconditionally rejected regardless of password state — fixed earlier this session, unrelated to and unaffected by this spec) — ORCID only works for this user once they've already linked it via Google/magic-link/password from `/account`. After a successful first login via any working method, a dismissible, once-per-login-session prompt encourages them to set a password.
3. **CSRF form fixes**: four HTML forms (`login.html`, `register.html`, `home.html`, `invitations/accept.html`) use plain `action="..."` instead of `th:action="@{...}"`, so Thymeleaf's Spring Security dialect never renders the `_csrf` hidden input — meaning CSRF protection (already enabled by Spring Security's default, confirmed during this session's research — the gap-analysis doc's "CSRF is disabled" claim is stale) silently rejects submissions to these forms.
4. **Deployment-layer documentation**: TLS termination and database encryption-at-rest are documented in `README.md` as deployment responsibilities, not enforced in code — consistent with the earlier scope decision to skip field-level encryption this round (the realistic threat model for a self-hosted single-tenant app is better served by transport/access-layer protection than by column encryption the app itself must still decrypt to function).
5. **`/dashboard` 403 fix for non-admin logins** (discovered during this spec's own writing, Section 4.4): the shared post-login redirect target only has an `ADMIN`-only handler at the real path `/admin/dashboard`, so every `REVIEWER`/`AUTHOR` login today redirects into a dead/forbidden route. Necessarily in scope because Section 4's password-prompt banner needs a real, working landing page for non-admin roles to render on.

**Explicitly out of scope, per prior scope decisions this session:**
- `ConferencePaymentConfig` (Stripe/PayPal secret fields) — fully deferred to roadmap item #7 (payment credentials externalized).
- Field-level encryption of any other personal-data column (emails, bios, free-text review comments, etc.) — deferred; TLS + DB access control is the baseline instead.
- Consent capture, data retention limits, deletion workflows, breach-notification process.

## 2. First-run admin credential

### 2.1 Remove the static seed

`data.sql`'s admin `INSERT` (already fixed to a placeholder `admin@example.org`/`ChangeMe123!` earlier this session — see commit `e2e24bf`) is removed entirely. The conference + sub-themes seed rows stay.

### 2.2 First-boot runner

New `@Component` implementing `ApplicationRunner`, in `org.confcms.cms.auth` (matches where `AuthService`/`MagicLinkService` already live — `org.confcms.cms.auth.service` — this runner is a thin orchestration piece, not itself a service, but the package boundary is a judgment call for implementation time):

```java
@Component
@RequiredArgsConstructor
public class FirstRunAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstRunAdminInitializer.class);
    private static final String ADMIN_EMAIL = "asakahatapitiya@gmail.com";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (!userRepository.findByRole(Role.ADMIN).isEmpty()) {
            return; // already has at least one admin -- no-op, every subsequent boot
        }

        String generatedPassword = generateSecurePassword();

        User admin = new User();
        admin.setEmail(ADMIN_EMAIL);
        admin.setFullName("System Administrator");
        admin.setRole(Role.ADMIN);
        admin.setPasswordHash(passwordEncoder.encode(generatedPassword));
        admin.setEnabled(true);
        userRepository.save(admin);

        log.info("=====================================================");
        log.info(" GENERATED ADMIN ACCOUNT (save this now, shown once)");
        log.info(" Email:    {}", ADMIN_EMAIL);
        log.info(" Password: {}", generatedPassword);
        log.info("=====================================================");
    }

    private String generateSecurePassword() {
        // 24 bytes of SecureRandom entropy, URL-safe base64 -- long enough that
        // dictionary/brute-force attacks are impractical, short enough to type
        // from a log line if the admin can't copy-paste (e.g. reading a
        // container's stdout over a remote terminal).
        byte[] randomBytes = new byte[24];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
```

No `UserIdentity(provider="local")` row is created for this account — matches the pre-existing gap already identified and NOT yet fixed for `AuthService.registerUser`-created accounts (out of scope here; this runner mirrors whatever `AuthService.registerUser` does today, and that gap is tracked separately, not reopened by this feature).

**Profile gating:** the runner is unconditional (runs in every profile) but its no-op check (`findByRole(Role.ADMIN)` non-empty) means it never fires against the `dev` profile in practice, since `data-dev.sql` already seeds `admin@example.com`/`admin` with `ADMIN` role before this runner's `run()` executes (Spring's `ApplicationRunner`s execute after `CommandLineRunner`s and after the datasource/SQL-init phase completes, so the dev seed is already present by the time this checks). No `@Profile` annotation is needed — the existing-admin check is a sufficient, simpler guard than duplicating profile logic.

### 2.3 README update

Replace the fixed-credential section with a description of this flow: check the server log on first boot for the generated password, change it immediately after logging in (existing account-settings "Set/change password" flow already covers this).

## 3. Admin-created passwordless users

### 3.1 New service method

`AuthService` gains a new method, mirroring `registerUser`'s duplicate-email guard exactly but skipping password/identity creation:

```java
@Transactional
public User createUserDirect(String email, String fullName, Role role) {
    if (userRepository.findByEmail(email).isPresent()) {
        throw new IllegalArgumentException("Email already in use");
    }

    User user = new User();
    user.setEmail(email);
    user.setFullName(fullName);
    user.setRole(role);
    user.setEnabled(true);
    // passwordHash intentionally left null -- this user has no local credential yet.
    // They authenticate via Google, ORCID, or magic link (all already match an
    // existing User by email with no password requirement); a "local" UserIdentity
    // is created lazily, the same way PasswordResetService.resetPassword already
    // does, the first time they actually set a password.

    return userRepository.save(user);
}
```

### 3.2 New controller

`AdminUserController` (or similar — package `org.confcms.cms.web.controller`, matching every other controller in this codebase, per established convention):

- `@Controller`, `@RequestMapping("/admin/users")`, `@PreAuthorize("hasRole('ADMIN')")` — this one IS genuinely admin-only, unlike the `/admin/decisions`/`/admin/invitations` controllers that intentionally also allow chair/co-chair `REVIEWER`s (no committee-role equivalent exists for "create any user").
  Confirmed against current `SecurityConfig.java`/`DevSecurityConfig.java`: the existing ADMIN-only URL matcher only covers `/admin/conference/**` (from this session's earlier `/admin/**` role-mismatch fix, which deliberately narrowed a too-broad matcher — see commit `6c62460`). `/admin/users/**` is NOT covered and would fall through to `.anyRequest().authenticated()` (any logged-in user, not ADMIN specifically) at the URL-filter layer — `@PreAuthorize("hasRole('ADMIN')")` on the controller is the only enforcement without a matcher change. Add `.requestMatchers("/admin/users/**").hasRole("ADMIN")` alongside the existing `/admin/conference/**` line in both `SecurityConfig` and `DevSecurityConfig`, as defense-in-depth consistent with that existing line's own pattern — not because `@PreAuthorize` alone is insufficient, but because this codebase's established convention pairs a URL-level matcher with the method-level check for genuinely admin-only areas.
- `GET /admin/users/new` — simple form (email, full name, role dropdown).
- `POST /admin/users/new` — calls `authService.createUserDirect(...)`, catches `IllegalArgumentException` for the duplicate-email case, redirects with a success/error message.

## 4. Password-set prompt for passwordless users

### 4.1 The structural gap this section resolves

No centralized `AuthenticationSuccessHandler` exists anywhere in this codebase today (confirmed absent) — form login relies on `defaultSuccessUrl`, OAuth2 falls back to Spring's built-in default success handler (no `defaultSuccessUrl` configured for it at all), and magic link is a fully custom filter with its own `sendRedirect`. Three independent, uncoordinated success paths. `CustomOAuth2UserService.resolveLocalUser`/`loadUser` have no `HttpServletRequest`/session access today (`OAuth2UserRequest` doesn't expose one), so the prompt-flag cannot be set from inside the account-resolution logic itself — it must be set from wherever each path's actual authenticated-response is being built, where request/session access already exists.

### 4.2 Design

Add one shared `AuthenticationSuccessHandler` bean, `PasswordPromptAuthenticationSuccessHandler`, wired into `formLogin().successHandler(...)` and `oauth2Login().successHandler(...)` in both `SecurityConfig` and `DevSecurityConfig` (replacing the plain `defaultSuccessUrl("/dashboard", true)` calls with this handler):

```java
@Component
@RequiredArgsConstructor
public class PasswordPromptAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;

    public PasswordPromptAuthenticationSuccessHandler() {
        setDefaultTargetUrl("/dashboard");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        markPromptIfPasswordless(request, authentication.getName());
        super.onAuthenticationSuccess(request, response, authentication);
    }

    void markPromptIfPasswordless(HttpServletRequest request, String email) {
        userRepository.findByEmail(email)
                .filter(user -> user.getPasswordHash() == null)
                .ifPresent(user -> request.getSession().setAttribute("passwordPromptPending", true));
    }
}
```

Extends `SimpleUrlAuthenticationSuccessHandler` (not `SavedRequestAwareAuthenticationSuccessHandler`) with `setAlwaysUseDefaultTargetUrl(true)`, matching the exact existing behavior of `defaultSuccessUrl("/dashboard", true)` byte-for-byte — this fix must not silently change where a user lands after login as a side effect of the password-prompt addition; that would be unrelated scope creep bundled into a security fix.

For **magic link**, which bypasses Spring Security's success-handler mechanism entirely, the same check runs directly inside `MagicLinkAuthenticationFilter`, right before its existing `response.sendRedirect("/dashboard")` call — it already has `request`/`response` in scope as a servlet filter, so no new access needs to be threaded in. Rather than duplicate the "look up by email, check null passwordHash, set session attribute" logic in two places, `PasswordPromptAuthenticationSuccessHandler.markPromptIfPasswordless` is package-visible (already shown as package-private, no access modifier, in Section 4.2's listing) and the filter calls it directly:

```java
// MagicLinkAuthenticationFilter, added field:
private final PasswordPromptAuthenticationSuccessHandler passwordPromptHandler;

// ...inside doFilterInternal, right before the existing response.sendRedirect("/dashboard"):
passwordPromptHandler.markPromptIfPasswordless(request, result.getName());
response.sendRedirect("/dashboard");
```

`MagicLinkAuthenticationFilter` is constructed manually today (`new MagicLinkAuthenticationFilter(magicLinkAuthenticationManager)` inside `SecurityConfig`/`DevSecurityConfig`'s `securityFilterChain` method, not a `@Component`), so `PasswordPromptAuthenticationSuccessHandler` (itself a `@Component`, injectable into `SecurityConfig`/`DevSecurityConfig` as a constructor field) is passed into the filter's constructor at that same call site, alongside the existing `magicLinkAuthenticationManager` argument.

### 4.3 Banner UI

Decision: the banner renders only on `/dashboard` and `/account` — the two pages every authenticated user reliably passes through at or shortly after login (form login and magic link both redirect to `/dashboard` by default; `/account` is the natural place to look for anything account-related). A `templates/fragments/` directory exists (`navbar.html`, `footer.html`, `head.html`, etc.) but is confirmed unused by any current template (`grep` for any `th:replace`/`th:insert` referencing them returns zero matches) — there is no live shared-layout mechanism to hook a banner into today, so introducing a `HandlerInterceptor` purely to project a session attribute onto every page's model would be new cross-cutting infrastructure for a banner two pages already cover well enough. A user deep-linking straight past both pages on a given login simply sees the prompt on their next visit to either one, or their next login — consistent with "once per login session, not nagging," not a functional gap.

`AdminController` (the existing `/admin/dashboard` handler) and `AccountSettingsController.show` both add `model.addAttribute("passwordPromptPending", request.getSession().getAttribute("passwordPromptPending") != null)` to their existing `Model` population, reading the session attribute directly (no service layer needed for a one-line session read). `DashboardController` (Section 4.4, new) does the same.

### 4.4 A genuine, separate bug this design depends on: `/dashboard` 403s for every non-admin login

Confirmed during spec-writing: `/dashboard` — the shared post-login redirect target for form login (`defaultSuccessUrl`), magic link (`MagicLinkAuthenticationFilter`'s `sendRedirect`), and (implicitly, via this feature's Section 4.2 handler) OAuth2 alike — has exactly one handler in the entire codebase: `AdminController.dashboard()`, mapped at class-level `@RequestMapping("/admin")` + method-level `@GetMapping("/dashboard")` (i.e. the real path is `/admin/dashboard`, not `/dashboard`), and class-level `@PreAuthorize("hasRole('ADMIN')")`. **Every REVIEWER or AUTHOR who logs in today via any method is redirected to a URL that resolves to nothing, or — if Spring's routing coincidentally matches it some other way — a 403.** This predates this feature and is unrelated to the password-prompt work, but Section 4.3's banner needs a real, working, role-appropriate landing page to render on for non-admin users, so this spec fixes it as necessarily-in-scope rather than building the banner atop a page that doesn't work for two of three roles.

**Fix:** a new `DashboardController`:

```java
@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final UserRepository userRepository;

    @GetMapping("/dashboard")
    public String dashboard(HttpServletRequest request, Model model) {
        User user = currentUser();
        if (user.getRole() == Role.ADMIN) {
            return "redirect:/admin/dashboard";
        }
        model.addAttribute("user", user);
        model.addAttribute("passwordPromptPending", request.getSession().getAttribute("passwordPromptPending") != null);
        return "dashboard"; // new, minimal template
    }

    private User currentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
```

A new, minimal `dashboard.html` for REVIEWER/AUTHOR: a welcome line and the password-prompt banner slot (Section 4.3) only. Confirmed: every existing template is listed under `src/main/resources/templates/` today (`account_settings.html`, `admin/*`, `auth/*`, `public/*`, `login.html`, `invitations/accept.html`, plus the unused `fragments/*`) — none serve a REVIEWER or AUTHOR role; `/review/my` and `/submission`'s equivalents are REST endpoints (`ReviewRestController`, `SubmissionRestController`, confirmed JSON-returning, not HTML views) with no page wrapping them. This new `dashboard.html` is genuinely the first HTML page either role has, and deliberately stays minimal (a welcome line plus the banner) rather than building out a real author/reviewer UI as a side effect of this fix — that full UI is separate, larger scope belonging to roadmap item #10 ("Author-facing submission status dashboard").

This is the one place in this spec where "necessarily in scope" genuinely expands beyond the four items in Section 1 — flagged explicitly rather than silently grown.

Banner content: "You're using a temporary/passwordless login. [Set a password now] [Skip for now]." "Skip for now" is a simple link/button that clears the session attribute (`GET`/`POST /account/dismiss-password-prompt` or similar, removing `passwordPromptPending` from the session) without navigating away. "Set a password now" links to the existing `/auth/forgot-password` flow (same reuse pattern `account_settings.html` already established) — NOT a new endpoint.

### 4.5 Reappearance logic

The session attribute is set fresh on every successful login (Section 4.2) — so dismissing it only affects the current session; the next login re-triggers the check, and since `passwordHash` is still null, the banner reappears. Once the user actually sets a password (via the existing `PasswordResetService.resetPassword`, which already creates the `"local"` `UserIdentity` row per existing behavior), `passwordHash` becomes non-null and the check in `markPromptIfPasswordless` naturally stops firing on all future logins — no explicit "stop asking" flag needed, the condition is self-correcting.

## 5. CSRF form fixes

Four templates, one-line change each: `action="..."` → `th:action="@{...}"`, exact URL preserved:

- `src/main/resources/templates/public/register.html:39` — `action="/register"` → `th:action="@{/register}"`
- `src/main/resources/templates/public/home.html:47` — `action="/logout"` → `th:action="@{/logout}"`
- `src/main/resources/templates/login.html:23` — `action="/login"` → `th:action="@{/login}"`
- `src/main/resources/templates/invitations/accept.html:25` — `action="/invitations/accept"` → `th:action="@{/invitations/accept}"`

No other changes to these files' structure or content.

## 6. Deployment documentation

Add a "Security & Deployment" section to `README.md` covering:
- TLS/HTTPS must be terminated at a reverse proxy or load balancer in front of this app in any real deployment — the app itself does not enforce or redirect to HTTPS.
- Database encryption-at-rest should be enabled at the database/infrastructure layer (e.g. cloud-managed database encryption, disk-level encryption) for any deployment handling real personal data — the application does not encrypt any column itself (confirmed: field-level encryption was explicitly scoped out this round in favor of this deployment-layer guidance).
- The first-run admin password (Section 2) must be retrieved from the server log immediately after first boot and changed via the account settings page — it is never persisted anywhere else, printed only once.

## 7. Error handling & edge cases

- `createUserDirect` duplicate email → `IllegalArgumentException`, mapped to a form error, same pattern as `registerUser`.
- First-run admin runner racing against a manual DB seed some other way (e.g. an operator manually inserts an admin row before first boot) → the `findByRole(ADMIN)` check still correctly no-ops, since it checks actual DB state, not a "have I run before" flag.
- A passwordless user who is later given a password reset link but never completes it → remains passwordless indefinitely, banner keeps reappearing — this is the intended, stable long-term state, not an error condition.
- Two admins created independently (the first-run one, plus later `createUserDirect`-made ADMIN-role accounts) → fully supported, no uniqueness constraint beyond email.

## 8. Testing approach

Consistent with this codebase's established convention (pure Mockito unit tests, no `@SpringBootTest`/`MockMvc`):
- `FirstRunAdminInitializer`: no-op when an admin exists, creates+logs when none does (test the logging via a captured `Logger`/`ListAppender` if practical, or simply assert the `User` is created correctly with a non-null encoded password — logging assertions are lower priority than the actual account-creation correctness).
- `AuthService.createUserDirect`: duplicate-email rejection, correct null-password/no-identity creation on success.
- `PasswordPromptAuthenticationSuccessHandler.markPromptIfPasswordless`: sets the session attribute for a passwordless user, does NOT set it for a user with a password, given a mocked `HttpServletRequest`/`HttpSession`.
- `MagicLinkAuthenticationFilter`'s equivalent check: extend the filter's existing test suite (already established this session) with a case confirming the session attribute is set on a passwordless user's successful magic-link login, and NOT set for a user with a password.
- Manual boot verification: the actual first-boot log output, and the four CSRF form fixes (curl-based, consistent with this session's established manual-verification pattern for anything template/session-rendering related that can't be meaningfully unit-tested).

## 9. Deferred / explicitly out of scope

- `ConferencePaymentConfig` credential handling — roadmap item #7.
- Field-level encryption of any personal-data column.
- Consent capture, retention/deletion workflows, breach-notification process.
- Retrofitting the "local" `UserIdentity` gap onto `AuthService.registerUser`-created accounts if one is later found missing (already fixed once this session for that method — see commit `328c4d0` — this spec does not reopen it).
- A shared page-layout/fragment system for the password-prompt banner — deferred in favor of the simpler dashboard/account-only rendering decided in Section 4.3.
