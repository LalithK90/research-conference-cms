# PDPA / Security Technical Baseline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the static seeded admin credential with a random first-boot password, let admins create passwordless users who set a password on first real login, fix four CSRF-broken forms, fix the `/dashboard` 403 for non-admin logins, and document deployment-layer TLS/encryption-at-rest responsibilities.

**Architecture:** A new `ApplicationRunner` creates the admin account once, on whichever boot finds no `ADMIN` user, logging a generated password. `AuthService` gains a sibling to `registerUser` that skips password/identity creation. A new shared `AuthenticationSuccessHandler` bean marks a session flag for passwordless users at every login entry point (form login, OAuth2, and — called directly, since it bypasses Spring's handler mechanism — magic link), replacing the three today's uncoordinated `defaultSuccessUrl`/redirect calls. A new `DashboardController` gives `REVIEWER`/`AUTHOR` logins a working `/dashboard` landing page (today only `ADMIN`'s `/admin/dashboard` exists), which is also where the password-prompt banner renders alongside `/account`.

**Tech Stack:** Spring Boot 3.5.8, Spring Security 6, Spring Data JPA, Thymeleaf, Lombok, JUnit 5 + Mockito + AssertJ (this codebase's only test style — no `@SpringBootTest`/`MockMvc` anywhere, and this plan does not introduce any).

**Global Constraints** (binding on every task, from `docs/superpowers/specs/2026-09-22-pdpa-security-baseline-design.md`):
- No `@SpringBootTest`, no `MockMvc`, no new test frameworks — pure Mockito unit tests on services/controllers/handlers, `@ExtendWith(MockitoExtension.class)`, AssertJ assertions, matching every existing test file.
- New controllers go in `org.confcms.cms.web.controller` (matches `AccountSettingsController`/`PersonInvitationController`).
- New services go in `org.confcms.cms.auth.service` (matches `AuthService`/`PasswordResetService`).
- `FirstRunAdminInitializer` and `PasswordPromptAuthenticationSuccessHandler` go in `org.confcms.cms.security` — they are security-infrastructure components wired directly into `SecurityConfig`/`DevSecurityConfig`, matching where `MagicLinkAuthenticationFilter`/`CustomOAuth2UserService` already live (not `org.confcms.cms.auth`, since neither is a thin orchestration piece over a service the way the spec's Section 2.2 draft package note left as a judgment call — this plan resolves that judgment call to `org.confcms.cms.security` for consistency with the filter/handler siblings they're wired next to).
- Every `SecurityConfig`/`DevSecurityConfig` change is applied identically to both files — they are structurally identical today and must stay that way.
- `passwordHash == null` is the sole signal for "passwordless user" everywhere in this feature — no new boolean flag on `User`.
- The admin email `asakahatapitiya@gmail.com` and the dev seed's `admin@example.com` are literal strings, not extracted into a shared constant — each lives only where the spec places it (`FirstRunAdminInitializer`, `data-dev.sql`), consistent with this codebase's existing style of inline literals for one-off seed/config values.

---

### Task 1: Remove static seed, add `FirstRunAdminInitializer`

**Files:**
- Modify: `src/main/resources/data.sql`
- Create: `src/main/java/org/confcms/cms/security/FirstRunAdminInitializer.java`
- Test: `src/test/java/org/confcms/cms/security/FirstRunAdminInitializerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/confcms/cms/security/FirstRunAdminInitializerTest.java`:

```java
package org.confcms.cms.security;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirstRunAdminInitializerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private ApplicationArguments applicationArguments;

    private FirstRunAdminInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new FirstRunAdminInitializer(userRepository, passwordEncoder);
    }

    @Test
    void noOpsWhenAnAdminAlreadyExists() {
        User existingAdmin = new User();
        existingAdmin.setEmail("admin@example.com");
        when(userRepository.findByRole(Role.ADMIN)).thenReturn(List.of(existingAdmin));

        initializer.run(applicationArguments);

        verify(userRepository, never()).save(any());
    }

    @Test
    void createsAdminWithGeneratedPasswordWhenNoneExists() {
        when(userRepository.findByRole(Role.ADMIN)).thenReturn(List.of());
        when(passwordEncoder.encode(any())).thenReturn("encoded-hash");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        initializer.run(applicationArguments);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("asakahatapitiya@gmail.com");
        assertThat(saved.getRole()).isEqualTo(Role.ADMIN);
        assertThat(saved.getPasswordHash()).isEqualTo("encoded-hash");
        assertThat(saved.isEnabled()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.security.FirstRunAdminInitializerTest"`
Expected: FAIL — compile error, `FirstRunAdminInitializer` does not exist yet.

- [ ] **Step 3: Create `FirstRunAdminInitializer`**

Create `src/main/java/org/confcms/cms/security/FirstRunAdminInitializer.java`:

```java
package org.confcms.cms.security;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

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
            return;
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
        byte[] randomBytes = new byte[24];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.security.FirstRunAdminInitializerTest"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Remove the static admin seed from `data.sql`**

Modify `src/main/resources/data.sql` — delete lines 17-22 (the `-- Default admin account...` comment block and the `INSERT INTO users` statement), keeping the conference + sub-themes inserts above it. Resulting file:

```sql
-- Default production-profile seed data: a minimal, generic starting conference.
-- Kept deliberately non-institution-specific (this is a reusable, self-hosted product,
-- not tied to any one university or event) -- replace or clear before a real deployment.
--
-- Schema is Hibernate-generated (spring.jpa.hibernate.ddl-auto=update in the default
-- profile), so this file only needs to match the current @Entity columns, not define them.

INSERT INTO conferences (title, venue, start_date, end_date, is_active, blind_review, contact_email, created_at, updated_at)
VALUES ('Sample Research Conference', 'Your Venue Here', '2026-01-01', '2026-01-03', true, false, 'info@example.org', NOW(), NOW());

SET @conference_id = LAST_INSERT_ID();

INSERT INTO sub_themes (conference_id, name, description, created_at, updated_at) VALUES
(@conference_id, 'Track A', 'Replace with your conference''s first track/theme.', NOW(), NOW()),
(@conference_id, 'Track B', 'Replace with your conference''s second track/theme.', NOW(), NOW());

-- Admin account is no longer seeded here. FirstRunAdminInitializer (see
-- org.confcms.cms.security) creates asakahatapitiya@gmail.com with a randomly
-- generated password on first boot when no ADMIN user exists yet, and logs the
-- password once. See README.md for retrieval instructions.
```

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/data.sql src/main/java/org/confcms/cms/security/FirstRunAdminInitializer.java src/test/java/org/confcms/cms/security/FirstRunAdminInitializerTest.java
git commit -m "feat: generate first-run admin password instead of seeding a static credential"
```

---

### Task 2: `AuthService.createUserDirect` for admin-created passwordless users

**Files:**
- Modify: `src/main/java/org/confcms/cms/auth/service/AuthService.java`
- Test: Modify `src/test/java/org/confcms/cms/auth/service/AuthServiceTest.java`

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/org/confcms/cms/auth/service/AuthServiceTest.java`, inside the `AuthServiceTest` class (after `registerUserCreatesUserAndLocalIdentity`):

```java
    @Test
    void createUserDirectRejectsExistingEmail() {
        when(userRepository.findByEmail("taken@example.com")).thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> service.createUserDirect("taken@example.com", "Someone", Role.REVIEWER))
                .isInstanceOf(IllegalArgumentException.class);

        verify(userRepository, never()).save(any());
        verify(userIdentityRepository, never()).save(any());
    }

    @Test
    void createUserDirectCreatesUserWithNoPasswordAndNoIdentity() {
        when(userRepository.findByEmail("newreviewer@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(2L);
            return u;
        });

        User result = service.createUserDirect("newreviewer@example.com", "New Reviewer", Role.REVIEWER);

        assertThat(result.getEmail()).isEqualTo("newreviewer@example.com");
        assertThat(result.getFullName()).isEqualTo("New Reviewer");
        assertThat(result.getRole()).isEqualTo(Role.REVIEWER);
        assertThat(result.getPasswordHash()).isNull();
        assertThat(result.isEnabled()).isTrue();

        verify(userIdentityRepository, never()).save(any());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.auth.service.AuthServiceTest"`
Expected: FAIL — compile error, `createUserDirect` does not exist on `AuthService`.

- [ ] **Step 3: Add `createUserDirect` to `AuthService`**

Modify `src/main/java/org/confcms/cms/auth/service/AuthService.java` — add this method after `registerUser`:

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
        // passwordHash intentionally left null -- this user authenticates via
        // Google, ORCID, or magic link first; a "local" UserIdentity is created
        // lazily the same way PasswordResetService.resetPassword already does,
        // the first time they actually set a password.

        return userRepository.save(user);
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.auth.service.AuthServiceTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/auth/service/AuthService.java src/test/java/org/confcms/cms/auth/service/AuthServiceTest.java
git commit -m "feat: add AuthService.createUserDirect for admin-created passwordless users"
```

---

### Task 3: `AdminUserController` + create-user form

**Files:**
- Create: `src/main/java/org/confcms/cms/web/controller/AdminUserController.java`
- Create: `src/main/resources/templates/admin/users_new.html`
- Modify: `src/main/java/org/confcms/cms/config/SecurityConfig.java`
- Modify: `src/main/java/org/confcms/cms/config/DevSecurityConfig.java`
- Test: `src/test/java/org/confcms/cms/web/controller/AdminUserControllerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/confcms/cms/web/controller/AdminUserControllerTest.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.auth.service.AuthService;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    @Mock
    private AuthService authService;

    private AdminUserController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminUserController(authService);
    }

    @Test
    void showFormReturnsTheCreateUserView() {
        Model model = new ExtendedModelMap();

        String view = controller.showForm(model);

        assertThat(view).isEqualTo("admin/users_new");
    }

    @Test
    void createRedirectsWithSuccessOnValidSubmission() {
        when(authService.createUserDirect("new@example.com", "New Person", Role.REVIEWER))
                .thenReturn(new User());
        Model model = new ExtendedModelMap();

        String view = controller.create("new@example.com", "New Person", Role.REVIEWER, model);

        assertThat(view).isEqualTo("redirect:/admin/users/new?created=true");
    }

    @Test
    void createRedisplaysFormWithErrorOnDuplicateEmail() {
        when(authService.createUserDirect(eq("taken@example.com"), any(), any()))
                .thenThrow(new IllegalArgumentException("Email already in use"));
        Model model = new ExtendedModelMap();

        String view = controller.create("taken@example.com", "Someone", Role.AUTHOR, model);

        assertThat(view).isEqualTo("admin/users_new");
        assertThat(model.getAttribute("error")).isEqualTo("Email already in use");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.AdminUserControllerTest"`
Expected: FAIL — compile error, `AdminUserController` does not exist yet.

- [ ] **Step 3: Create `AdminUserController`**

Create `src/main/java/org/confcms/cms/web/controller/AdminUserController.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.auth.service.AuthService;
import org.confcms.cms.core.security.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final AuthService authService;

    @GetMapping("/new")
    public String showForm(Model model) {
        return "admin/users_new";
    }

    @PostMapping("/new")
    public String create(@RequestParam String email, @RequestParam String fullName,
                          @RequestParam Role role, Model model) {
        try {
            authService.createUserDirect(email, fullName, role);
            return "redirect:/admin/users/new?created=true";
        } catch (IllegalArgumentException iae) {
            model.addAttribute("error", iae.getMessage());
            return "admin/users_new";
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.AdminUserControllerTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Create the form template**

Create `src/main/resources/templates/admin/users_new.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head>
    <meta charset="UTF-8">
    <title>Create User - Conference CMS</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/app.css" rel="stylesheet">
</head>

<body>
    <nav class="navbar navbar-dark bg-dark">
        <div class="container">
            <a class="navbar-brand" href="/admin/dashboard">CMS Admin</a>
            <a class="btn btn-outline-light" href="/logout">Logout</a>
        </div>
    </nav>

    <div class="container mt-4">
        <div class="row justify-content-center">
            <div class="col-md-6">
                <h2 class="mb-4">Create User</h2>

                <div th:if="${param.created}" class="alert alert-success">
                    User created. They can sign in via Google or a magic link (no password set).
                </div>
                <div th:if="${error}" class="alert alert-danger" th:text="${error}"></div>

                <form method="post" th:action="@{/admin/users/new}">
                    <div class="mb-3">
                        <label class="form-label">Email</label>
                        <input type="email" class="form-control" name="email" required>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Full Name</label>
                        <input type="text" class="form-control" name="fullName" required>
                    </div>
                    <div class="mb-3">
                        <label class="form-label">Role</label>
                        <select name="role" class="form-select" required>
                            <option value="AUTHOR">Author</option>
                            <option value="REVIEWER">Reviewer</option>
                            <option value="ADMIN">Admin</option>
                        </select>
                    </div>
                    <button type="submit" class="btn btn-primary">Create User</button>
                </form>
            </div>
        </div>
    </div>
</body>

</html>
```

- [ ] **Step 6: Add the `/admin/users/**` URL matcher to both security configs**

Modify `src/main/java/org/confcms/cms/config/SecurityConfig.java` line 51:

```java
                .requestMatchers("/admin/conference/**").hasRole("ADMIN")
                .requestMatchers("/admin/users/**").hasRole("ADMIN")
```

Modify `src/main/java/org/confcms/cms/config/DevSecurityConfig.java` line 50, identically:

```java
                .requestMatchers("/admin/conference/**").hasRole("ADMIN")
                .requestMatchers("/admin/users/**").hasRole("ADMIN")
```

- [ ] **Step 7: Compile the full project**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/AdminUserController.java src/main/resources/templates/admin/users_new.html src/main/java/org/confcms/cms/config/SecurityConfig.java src/main/java/org/confcms/cms/config/DevSecurityConfig.java src/test/java/org/confcms/cms/web/controller/AdminUserControllerTest.java
git commit -m "feat: add admin-only create-user-directly page"
```

---

### Task 4: `PasswordPromptAuthenticationSuccessHandler`

**Files:**
- Create: `src/main/java/org/confcms/cms/security/PasswordPromptAuthenticationSuccessHandler.java`
- Test: `src/test/java/org/confcms/cms/security/PasswordPromptAuthenticationSuccessHandlerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/confcms/cms/security/PasswordPromptAuthenticationSuccessHandlerTest.java`:

```java
package org.confcms.cms.security;

import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordPromptAuthenticationSuccessHandlerTest {

    @Mock
    private UserRepository userRepository;

    private PasswordPromptAuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PasswordPromptAuthenticationSuccessHandler(userRepository);
    }

    @Test
    void setsSessionAttributeForPasswordlessUser() {
        User passwordless = new User();
        passwordless.setEmail("passwordless@example.com");
        passwordless.setPasswordHash(null);
        when(userRepository.findByEmail("passwordless@example.com")).thenReturn(Optional.of(passwordless));

        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.markPromptIfPasswordless(request, "passwordless@example.com");

        assertThat(request.getSession(false)).isNotNull();
        assertThat(request.getSession(false).getAttribute("passwordPromptPending")).isEqualTo(true);
    }

    @Test
    void doesNotSetSessionAttributeForUserWithPassword() {
        User withPassword = new User();
        withPassword.setEmail("hasone@example.com");
        withPassword.setPasswordHash("hashed");
        when(userRepository.findByEmail("hasone@example.com")).thenReturn(Optional.of(withPassword));

        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.markPromptIfPasswordless(request, "hasone@example.com");

        assertThat(request.getSession(false)).isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.security.PasswordPromptAuthenticationSuccessHandlerTest"`
Expected: FAIL — compile error, class does not exist yet.

- [ ] **Step 3: Create `PasswordPromptAuthenticationSuccessHandler`**

Create `src/main/java/org/confcms/cms/security/PasswordPromptAuthenticationSuccessHandler.java`:

```java
package org.confcms.cms.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.confcms.cms.repository.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class PasswordPromptAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;

    public PasswordPromptAuthenticationSuccessHandler(UserRepository userRepository) {
        this.userRepository = userRepository;
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.security.PasswordPromptAuthenticationSuccessHandlerTest"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/security/PasswordPromptAuthenticationSuccessHandler.java src/test/java/org/confcms/cms/security/PasswordPromptAuthenticationSuccessHandlerTest.java
git commit -m "feat: add password-prompt success handler for passwordless-user logins"
```

---

### Task 5: Wire the handler into `MagicLinkAuthenticationFilter`, `SecurityConfig`, `DevSecurityConfig`

**Files:**
- Modify: `src/main/java/org/confcms/cms/security/MagicLinkAuthenticationFilter.java`
- Modify: `src/test/java/org/confcms/cms/security/MagicLinkAuthenticationFilterTest.java`
- Modify: `src/main/java/org/confcms/cms/config/SecurityConfig.java`
- Modify: `src/main/java/org/confcms/cms/config/DevSecurityConfig.java`

- [ ] **Step 1: Write the failing test**

Add to `src/test/java/org/confcms/cms/security/MagicLinkAuthenticationFilterTest.java`. First, add two new fields and update `setUp()`:

```java
    @Mock
    private org.confcms.cms.repository.UserRepository userRepository;

    private PasswordPromptAuthenticationSuccessHandler passwordPromptHandler;

    @BeforeEach
    void setUp() {
        passwordPromptHandler = new PasswordPromptAuthenticationSuccessHandler(userRepository);
        filter = new MagicLinkAuthenticationFilter(authenticationManager, passwordPromptHandler);
    }
```

This replaces the existing `setUp()` (which currently reads `filter = new MagicLinkAuthenticationFilter(authenticationManager);`). Then add these two new test methods at the end of the class, before the closing brace:

```java
    @Test
    void validTokenSetsPasswordPromptForPasswordlessUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/magic/verify");
        request.setServletPath("/auth/magic/verify");
        request.setParameter("token", "good-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        org.confcms.cms.domain.User passwordless = new org.confcms.cms.domain.User();
        passwordless.setEmail("passwordless@example.com");
        passwordless.setPasswordHash(null);
        when(userRepository.findByEmail("passwordless@example.com")).thenReturn(java.util.Optional.of(passwordless));
        when(authenticatedResult.getName()).thenReturn("passwordless@example.com");
        when(authenticationManager.authenticate(any(MagicLinkAuthenticationToken.class)))
                .thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(request.getSession(false).getAttribute("passwordPromptPending")).isEqualTo(true);
    }

    @Test
    void validTokenDoesNotSetPasswordPromptForUserWithPassword() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/magic/verify");
        request.setServletPath("/auth/magic/verify");
        request.setParameter("token", "good-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        org.confcms.cms.domain.User withPassword = new org.confcms.cms.domain.User();
        withPassword.setEmail("hasone@example.com");
        withPassword.setPasswordHash("hashed");
        when(userRepository.findByEmail("hasone@example.com")).thenReturn(java.util.Optional.of(withPassword));
        when(authenticatedResult.getName()).thenReturn("hasone@example.com");
        when(authenticationManager.authenticate(any(MagicLinkAuthenticationToken.class)))
                .thenReturn(authenticatedResult);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(request.getSession(false).getAttribute("passwordPromptPending")).isNull();
    }
```

Note: the four pre-existing tests (`validTokenPersistsContextAndRedirectsToDashboard`, `validTokenRenamesAnExistingSessionId`, `doesNotAttemptToRenameASessionThatDidNotExistYet`) call `authenticationManager.authenticate(...)` and expect `authenticatedResult` back — they don't stub `authenticatedResult.getName()`, which is fine since Mockito mocks return `null` from unstubbed methods by default and `null` is a safe input to `userRepository.findByEmail` once that's also unstubbed (Mockito's default for an unstubbed mock method returning `Optional` is `null`, not `Optional.empty()` — this matters for Step 3 below).

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `./gradlew test --tests "org.confcms.cms.security.MagicLinkAuthenticationFilterTest"`
Expected: FAIL — compile error, `MagicLinkAuthenticationFilter` constructor doesn't accept a second argument yet.

- [ ] **Step 3: Add the constructor parameter and call to `MagicLinkAuthenticationFilter`**

Modify `src/main/java/org/confcms/cms/security/MagicLinkAuthenticationFilter.java`:

```java
    private final AuthenticationManager authenticationManager;
    private final PasswordPromptAuthenticationSuccessHandler passwordPromptHandler;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

    public MagicLinkAuthenticationFilter(AuthenticationManager authenticationManager,
                                          PasswordPromptAuthenticationSuccessHandler passwordPromptHandler) {
        this.authenticationManager = authenticationManager;
        this.passwordPromptHandler = passwordPromptHandler;
    }
```

This replaces the existing field declarations and constructor. Then update the success path — replace:

```java
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(result);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            response.sendRedirect("/dashboard");
```

with:

```java
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(result);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, request, response);

            passwordPromptHandler.markPromptIfPasswordless(request, result.getName());
            response.sendRedirect("/dashboard");
```

Given `markPromptIfPasswordless` calls `userRepository.findByEmail(email)` and the four pre-existing tests leave `userRepository` unstubbed for whatever `authenticatedResult.getName()` returns (`null` by default), confirm `UserRepository.findByEmail` is called with `null` safely — it returns `Optional` and Mockito's default stub for an unstubbed method returning `Optional` is `null`, which `Optional.filter` cannot be called on. To keep the four pre-existing tests green without modifying their intent, add `lenient()` stubbing is not the fix here — instead verify in Step 4 whether they still pass as-is; if `NullPointerException` occurs, the correct fix is that those four tests do not need `authenticatedResult.getName()` to return anything meaningful, so stub it explicitly there too (see Step 3b).

- [ ] **Step 3b: If the four pre-existing tests fail with NPE, stub `getName()` for them**

If Step 4 shows `validTokenPersistsContextAndRedirectsToDashboard`, `validTokenRenamesAnExistingSessionId`, or `doesNotAttemptToRenameASessionThatDidNotExistYet` failing with a `NullPointerException` (because `userRepository.findByEmail(null)` returns `null` rather than `Optional`), add `when(authenticatedResult.getName()).thenReturn("someone@example.com");` and `when(userRepository.findByEmail("someone@example.com")).thenReturn(java.util.Optional.empty());` to each of those three tests' bodies, right before the `filter.doFilterInternal(...)` call. This keeps `markPromptIfPasswordless` a true no-op for those tests (empty `Optional`, `filter` finds nothing to act on) without changing what each test asserts.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.security.MagicLinkAuthenticationFilterTest"`
Expected: PASS, 8 tests (6 original + 2 new; apply Step 3b if any of the original 3 needing `getName()`/`findByEmail` stubs failed).

- [ ] **Step 5: Wire the handler into both security configs**

Modify `src/main/java/org/confcms/cms/config/SecurityConfig.java`:

Add the field and constructor dependency (via `@RequiredArgsConstructor`, just add the field):

```java
    private final CustomOAuth2UserService customOAuth2UserService;
    private final PasswordPromptAuthenticationSuccessHandler passwordPromptAuthenticationSuccessHandler;
```

Replace the magic-link filter construction:

```java
            .addFilterBefore(new MagicLinkAuthenticationFilter(magicLinkAuthenticationManager, passwordPromptAuthenticationSuccessHandler), UsernamePasswordAuthenticationFilter.class)
```

Replace the `formLogin` block's `defaultSuccessUrl`:

```java
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(passwordPromptAuthenticationSuccessHandler)
                .permitAll()
            )
```

Replace the `oauth2Login` block to add the same handler:

```java
        if (anyOAuth2ProviderConfigured) {
            http.oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                .successHandler(passwordPromptAuthenticationSuccessHandler)
            );
        }
```

Apply the identical four changes to `src/main/java/org/confcms/cms/config/DevSecurityConfig.java` (same field addition, same three replacements — the two files are structurally identical).

- [ ] **Step 6: Compile the full project**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/security/MagicLinkAuthenticationFilter.java src/test/java/org/confcms/cms/security/MagicLinkAuthenticationFilterTest.java src/main/java/org/confcms/cms/config/SecurityConfig.java src/main/java/org/confcms/cms/config/DevSecurityConfig.java
git commit -m "feat: wire password-prompt handler into form login, oauth2 login, and magic link"
```

---

### Task 6: `DashboardController` fixing the `/dashboard` 403, plus the banner on `/admin/dashboard` and `/account`

**Files:**
- Create: `src/main/java/org/confcms/cms/web/controller/DashboardController.java`
- Create: `src/main/resources/templates/dashboard.html`
- Modify: `src/main/java/org/confcms/cms/admin/controller/AdminController.java`
- Modify: `src/main/resources/templates/admin/dashboard.html`
- Modify: `src/main/java/org/confcms/cms/web/controller/AccountSettingsController.java`
- Modify: `src/main/resources/templates/account_settings.html`
- Test: `src/test/java/org/confcms/cms/web/controller/DashboardControllerTest.java`
- Test: Modify `src/test/java/org/confcms/cms/web/controller/AccountSettingsControllerTest.java`

- [ ] **Step 1: Write the failing test for `DashboardController`**

Create `src/test/java/org/confcms/cms/web/controller/DashboardControllerTest.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock
    private UserRepository userRepository;

    private DashboardController controller;

    @BeforeEach
    void setUp() {
        controller = new DashboardController(userRepository);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, Collections.emptyList()));
    }

    @Test
    void adminIsRedirectedToAdminDashboard() {
        User admin = new User();
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticateAs(admin);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));

        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        String view = controller.dashboard(request, model);

        assertThat(view).isEqualTo("redirect:/admin/dashboard");
    }

    @Test
    void reviewerSeesTheMinimalDashboardWithNoPendingPrompt() {
        User reviewer = new User();
        reviewer.setEmail("reviewer@example.com");
        reviewer.setRole(Role.REVIEWER);
        authenticateAs(reviewer);
        when(userRepository.findByEmail("reviewer@example.com")).thenReturn(Optional.of(reviewer));

        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        String view = controller.dashboard(request, model);

        assertThat(view).isEqualTo("dashboard");
        assertThat(model.getAttribute("user")).isEqualTo(reviewer);
        assertThat(model.getAttribute("passwordPromptPending")).isEqualTo(false);
    }

    @Test
    void authorSeesPasswordPromptWhenSessionFlagIsSet() {
        User author = new User();
        author.setEmail("author@example.com");
        author.setRole(Role.AUTHOR);
        authenticateAs(author);
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(author));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("passwordPromptPending", true);
        Model model = new ExtendedModelMap();

        String view = controller.dashboard(request, model);

        assertThat(view).isEqualTo("dashboard");
        assertThat(model.getAttribute("passwordPromptPending")).isEqualTo(true);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.DashboardControllerTest"`
Expected: FAIL — compile error, `DashboardController` does not exist yet.

- [ ] **Step 3: Create `DashboardController`**

Create `src/main/java/org/confcms/cms/web/controller/DashboardController.java`:

```java
package org.confcms.cms.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

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
        return "dashboard";
    }

    private User currentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.DashboardControllerTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Create the minimal `dashboard.html`**

Create `src/main/resources/templates/dashboard.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head>
    <meta charset="UTF-8">
    <title>Dashboard - Conference CMS</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/app.css" rel="stylesheet">
</head>

<body class="bg-light">
    <nav class="navbar navbar-dark bg-dark">
        <div class="container">
            <a class="navbar-brand" href="/">Conference CMS</a>
            <a class="btn btn-outline-light" href="/logout">Logout</a>
        </div>
    </nav>

    <div class="container mt-4">
        <div th:if="${passwordPromptPending}" class="alert alert-warning d-flex justify-content-between align-items-center">
            <span>You're using a temporary/passwordless login.</span>
            <span>
                <a th:href="@{/auth/forgot-password}" class="btn btn-sm btn-primary">Set a password now</a>
                <a th:href="@{/account/dismiss-password-prompt}" class="btn btn-sm btn-outline-secondary">Skip for now</a>
            </span>
        </div>

        <h2>Welcome, <span th:text="${user.fullName}"></span></h2>
        <p class="text-muted" th:text="${user.role}"></p>
        <a th:href="@{/account}">Account Settings</a>
    </div>
</body>

</html>
```

- [ ] **Step 6: Add the `/account/dismiss-password-prompt` endpoint and banner attribute to `AccountSettingsController`**

Modify `src/main/java/org/confcms/cms/web/controller/AccountSettingsController.java` — replace the whole file:

```java
package org.confcms.cms.web.controller;

import jakarta.servlet.http.HttpServletRequest;
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
    public String show(HttpServletRequest request, Model model) {
        User user = actingUser();
        model.addAttribute("user", user);
        model.addAttribute("identities", userIdentityRepository.findByUserId(user.getId()));
        model.addAttribute("passwordPromptPending", request.getSession().getAttribute("passwordPromptPending") != null);
        return "account_settings";
    }

    @GetMapping("/account/dismiss-password-prompt")
    public String dismissPasswordPrompt(HttpServletRequest request) {
        request.getSession().removeAttribute("passwordPromptPending");
        return "redirect:/account";
    }
}
```

- [ ] **Step 7: Update `AccountSettingsControllerTest` for the new `HttpServletRequest` parameter**

Modify `src/test/java/org/confcms/cms/web/controller/AccountSettingsControllerTest.java` — add the import `import org.springframework.mock.web.MockHttpServletRequest;`, then replace the `showListsLinkedIdentitiesForCurrentUser` test body's call and add two new tests. Replace:

```java
        Model model = new ExtendedModelMap();
        String view = controller.show(model);

        assertThat(view).isEqualTo("account_settings");
        assertThat(model.getAttribute("identities")).isEqualTo(List.of(local, google));
        assertThat(model.getAttribute("user")).isEqualTo(user);
    }
}
```

with:

```java
        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();
        String view = controller.show(request, model);

        assertThat(view).isEqualTo("account_settings");
        assertThat(model.getAttribute("identities")).isEqualTo(List.of(local, google));
        assertThat(model.getAttribute("user")).isEqualTo(user);
        assertThat(model.getAttribute("passwordPromptPending")).isEqualTo(false);
    }

    @Test
    void showReportsPasswordPromptPendingWhenSessionFlagIsSet() {
        User user = new User();
        user.setId(1L);
        user.setEmail("author@example.com");
        user.setRole(Role.AUTHOR);
        authenticateAs(user);

        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));
        when(userIdentityRepository.findByUserId(1L)).thenReturn(List.of());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("passwordPromptPending", true);
        Model model = new ExtendedModelMap();

        controller.show(request, model);

        assertThat(model.getAttribute("passwordPromptPending")).isEqualTo(true);
    }

    @Test
    void dismissPasswordPromptClearsTheSessionFlagAndRedirects() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("passwordPromptPending", true);

        String view = controller.dismissPasswordPrompt(request);

        assertThat(view).isEqualTo("redirect:/account");
        assertThat(request.getSession(false).getAttribute("passwordPromptPending")).isNull();
    }
}
```

- [ ] **Step 8: Add the banner to `account_settings.html`**

Modify `src/main/resources/templates/account_settings.html` — insert right after the opening `<div class="card-body p-5">` line (before `<h2 class="mb-4">Account Settings</h2>`):

```html
                        <div th:if="${passwordPromptPending}" class="alert alert-warning d-flex justify-content-between align-items-center">
                            <span>You're using a temporary/passwordless login.</span>
                            <span>
                                <a th:href="@{/auth/forgot-password}" class="btn btn-sm btn-primary">Set a password now</a>
                                <a th:href="@{/account/dismiss-password-prompt}" class="btn btn-sm btn-outline-secondary">Skip for now</a>
                            </span>
                        </div>

```

- [ ] **Step 9: Write the failing test for `AdminController`'s banner attribute**

No `AdminControllerTest.java` exists yet (confirmed: `find src/test -iname "AdminControllerTest.java"` returns nothing). Create `src/test/java/org/confcms/cms/admin/controller/AdminControllerTest.java`:

```java
package org.confcms.cms.admin.controller;

import org.confcms.cms.submission.service.SubmissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private SubmissionService submissionService;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(submissionService);
    }

    @Test
    void dashboardHasNoPendingPromptByDefault() {
        when(submissionService.getAllPapers()).thenReturn(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        String view = controller.dashboard(request, model);

        assertThat(view).isEqualTo("admin/dashboard");
        assertThat(model.getAttribute("passwordPromptPending")).isEqualTo(false);
    }

    @Test
    void dashboardReportsPendingPromptWhenSessionFlagIsSet() {
        when(submissionService.getAllPapers()).thenReturn(List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("passwordPromptPending", true);
        Model model = new ExtendedModelMap();

        controller.dashboard(request, model);

        assertThat(model.getAttribute("passwordPromptPending")).isEqualTo(true);
    }
}
```

Run: `./gradlew test --tests "org.confcms.cms.admin.controller.AdminControllerTest"`
Expected: FAIL — compile error, `AdminController.dashboard` does not accept an `HttpServletRequest` yet.

- [ ] **Step 10: Add the `HttpServletRequest` parameter and banner attribute to `AdminController.dashboard`**

Modify `src/main/java/org/confcms/cms/admin/controller/AdminController.java` — replace:

```java
    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("papers", submissionService.getAllPapers());
        return "admin/dashboard";
    }
```

with:

```java
    @GetMapping("/dashboard")
    public String dashboard(HttpServletRequest request, Model model) {
        model.addAttribute("papers", submissionService.getAllPapers());
        model.addAttribute("passwordPromptPending", request.getSession().getAttribute("passwordPromptPending") != null);
        return "admin/dashboard";
    }
```

Add the import `import jakarta.servlet.http.HttpServletRequest;` at the top of the file alongside the existing imports.

- [ ] **Step 11: Add the banner to `admin/dashboard.html`**

Modify `src/main/resources/templates/admin/dashboard.html` — insert right after the closing `</nav>` tag and before `<div class="container mt-4">`:

```html
    <div class="container mt-4" th:if="${passwordPromptPending}">
        <div class="alert alert-warning d-flex justify-content-between align-items-center">
            <span>You're using a temporary/passwordless login.</span>
            <span>
                <a th:href="@{/auth/forgot-password}" class="btn btn-sm btn-primary">Set a password now</a>
                <a th:href="@{/account/dismiss-password-prompt}" class="btn btn-sm btn-outline-secondary">Skip for now</a>
            </span>
        </div>
    </div>

```

(This is a second `<div class="container mt-4">` wrapping only the banner, immediately followed by the existing `<div class="container mt-4">` wrapping the submissions table — both render, Thymeleaf's `th:if` on the new one simply hides it when the flag is false.)

- [ ] **Step 12: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass (existing + all added in Tasks 1-6).

- [ ] **Step 13: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/DashboardController.java src/main/resources/templates/dashboard.html src/main/java/org/confcms/cms/admin/controller/AdminController.java src/main/resources/templates/admin/dashboard.html src/main/java/org/confcms/cms/web/controller/AccountSettingsController.java src/main/resources/templates/account_settings.html src/test/java/org/confcms/cms/web/controller/DashboardControllerTest.java src/test/java/org/confcms/cms/web/controller/AccountSettingsControllerTest.java src/test/java/org/confcms/cms/admin/controller/AdminControllerTest.java
git commit -m "fix: add working /dashboard for non-admin logins, render password-prompt banner"
```

---

### Task 7: CSRF form fixes

**Files:**
- Modify: `src/main/resources/templates/public/register.html`
- Modify: `src/main/resources/templates/public/home.html`
- Modify: `src/main/resources/templates/login.html`
- Modify: `src/main/resources/templates/invitations/accept.html`

This task is a batch of four identical one-line edits — no tests apply (server-rendered HTML attribute changes, consistent with this codebase's established manual-verification pattern for template-only changes; see spec Section 8).

- [ ] **Step 1: Fix `register.html`**

Modify `src/main/resources/templates/public/register.html` line 39 — replace:

```html
                            <form action="/register" method="post">
```

with:

```html
                            <form th:action="@{/register}" method="post">
```

- [ ] **Step 2: Fix `home.html`**

Modify `src/main/resources/templates/public/home.html` line 47 — replace:

```html
                        <form method="post" action="/logout" class="d-inline">
```

with:

```html
                        <form method="post" th:action="@{/logout}" class="d-inline">
```

- [ ] **Step 3: Fix `login.html`**

Modify `src/main/resources/templates/login.html` line 23 — replace:

```html
                        <form method="post" action="/login">
```

with:

```html
                        <form method="post" th:action="@{/login}">
```

- [ ] **Step 4: Fix `invitations/accept.html`**

Modify `src/main/resources/templates/invitations/accept.html` line 25 — replace:

```html
                            <form method="post" action="/invitations/accept">
```

with:

```html
                            <form method="post" th:action="@{/invitations/accept}">
```

- [ ] **Step 5: Boot the app and manually verify all four forms submit successfully**

Run: `./gradlew bootRun` (dev profile), then in a browser:
1. Visit `/login`, submit valid dev credentials (`admin@example.com` / `admin`) — expect successful login, not a 403.
2. Visit `/`, log in, click Logout — expect successful logout, not a 403.
3. Visit `/register` while logged in as an author with an active conference — expect the registration POST to succeed (or fail with a business-logic error, not a CSRF 403).
4. Use a valid invitation token at `/invitations/accept?token=...` and submit — expect success, not a CSRF 403.

Stop the app (`Ctrl+C`) once verified.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/public/register.html src/main/resources/templates/public/home.html src/main/resources/templates/login.html src/main/resources/templates/invitations/accept.html
git commit -m "fix: render CSRF token on four forms broken by plain HTML action attributes"
```

---

### Task 8: README updates (first-run admin flow + deployment security section)

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Replace the default-admin-credentials section**

Modify `README.md` — replace lines 80-84:

```markdown
4.  **Access the System**
    *   Open your browser and go to: `http://localhost:8080`
    *   **Default Admin Credentials** (seeded on first run — change this password immediately):
        *   Email: `admin@example.org`
        *   Password: `ChangeMe123!`
```

with:

```markdown
4.  **Access the System**
    *   Open your browser and go to: `http://localhost:8080`
    *   **First-run admin account**: on first boot (when no admin account exists yet), the
        application generates a random password for `asakahatapitiya@gmail.com` and prints it
        once to the server log:
        ```
        =====================================================
         GENERATED ADMIN ACCOUNT (save this now, shown once)
         Email:    asakahatapitiya@gmail.com
         Password: <random>
        =====================================================
        ```
        Copy the password from the log and log in immediately — it is never stored anywhere
        else in plaintext. Change it right away via Account Settings -> Set/change password.
```

- [ ] **Step 2: Add a Security & Deployment section**

Modify `README.md` — insert a new section right before `## 📜 Citation & Attribution`:

```markdown
## 🔒 Security & Deployment

This application handles personal data (names, emails, ORCID IDs, paper submissions) and
should be deployed with the following in place:

*   **TLS/HTTPS**: the application does not terminate TLS or redirect HTTP to HTTPS itself.
    Run it behind a reverse proxy or load balancer (nginx, Caddy, a cloud load balancer) that
    terminates TLS, in any deployment reachable over a public or untrusted network.
*   **Database encryption at rest**: the application does not encrypt any column itself.
    Enable encryption at rest at the database/infrastructure layer (cloud-managed database
    encryption, or disk-level encryption for a self-hosted MySQL instance) for any deployment
    handling real personal data.
*   **First-run admin password**: retrieve it from the server log immediately after first boot
    (see above) and change it via Account Settings. It is generated fresh per deployment and
    never checked into source control or configuration files.

```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: document first-run admin flow and deployment-layer security responsibilities"
```

---

### Task 9: Final verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, zero failures.

- [ ] **Step 2: Boot the app in dev profile and verify the full passwordless-user flow manually**

Run: `./gradlew bootRun` with the `dev` profile active. In a browser:
1. Log in as `admin@example.com` / `admin` (dev seed, unaffected by this feature).
2. Go to `/admin/users/new`, create a user with email `newreviewer@example.com`, name "New Reviewer", role Reviewer.
3. Confirm Google OAuth2 or magic-link is configured in dev (check `application-dev.properties`); if magic link is available, request one for `newreviewer@example.com` via `/auth/magic-link/request` (or whatever the existing request endpoint is — check `MagicLinkService`/its controller), and follow the emailed/logged link.
4. Confirm the login succeeds, lands on `/dashboard` (not a 403), and the password-prompt banner appears.
5. Click "Skip for now" — confirm the banner disappears and the session flag is cleared (revisit `/dashboard`, banner stays gone for this session).
6. Log out and log back in via the same passwordless method — confirm the banner reappears (fresh session).
7. Click "Set a password now", complete the reset-password flow, confirm `passwordHash` is now set (log in with the new password directly).
8. Log out and log back in with the new password — confirm the banner no longer appears.

Stop the app once verified.

- [ ] **Step 3: Verify the first-run admin flow on a genuinely empty database**

This only applies to the default (non-dev) profile, which uses a real empty MySQL database rather than H2's dev seed. If a MySQL instance is available for this verification, point `application.properties` at an empty `conference_cms` database, run `./gradlew bootRun` (default profile), and confirm the generated-admin log block appears with `asakahatapitiya@gmail.com`. If no MySQL instance is available in this environment, skip this step and rely on `FirstRunAdminInitializerTest` (Task 1) as the correctness check — note this in the final report rather than silently skipping.

- [ ] **Step 4: Report completion**

Summarize: all 9 tasks complete, full test suite green, manual verification results from Steps 2-3 above (including whether Step 3 was actually run or skipped for lack of a MySQL instance).
