# Access Audit Log, Duplicate-Paper Detection & GeoLite2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Record every successful login and paper download in a shared `AccessLog` table, flag byte-identical duplicate paper submissions across all conferences for chair review, and wire the currently-unused `GeoLite2-City.mmdb` to resolve login locations.

**Architecture:** One new `AccessLog` entity (login + paper-download events) written via a new `AccessLogService`, called from the existing shared login-success handler and from the one reviewer paper-download endpoint. A new `GeoLocationService` wraps a MaxMind `DatabaseReader` loaded from the classpath (moved out of the public `static/` directory), degrading gracefully to "no location" if the database is missing or an IP can't be resolved — never blocking login. `PaperVersion` gains a SHA-256 `contentHash` computed at upload time in all three `SubmissionService` upload paths; a hash collision against any prior version (any conference) sets a `possibleDuplicate` flag and a lightweight cross-reference, surfaced as a badge on the existing admin papers list. A new minimal `/admin/access-logs` page lists the most recent 100 events.

**Tech Stack:** Spring Boot 3.5.8, Spring Data JPA, `java.security.MessageDigest`/`java.util.HexFormat` (JDK standard library, no new dependency for hashing), `com.maxmind.geoip2:geoip2:4.3.0` (new dependency, version confirmed against Maven Central at plan-writing time), Thymeleaf, Lombok, JUnit 5 + Mockito + AssertJ (this codebase's only test style — no `@SpringBootTest`/`MockMvc` anywhere, and this plan does not introduce any).

**Global Constraints** (binding on every task, from `docs/superpowers/specs/2026-09-26-access-audit-log-design.md`):
- No `@SpringBootTest`, no `MockMvc`, no new test frameworks — pure Mockito unit tests, `@ExtendWith(MockitoExtension.class)`, AssertJ assertions.
- `AccessLog`/`AccessEventType` live in `org.confcms.cms.domain`, matching where `Conference`/`ConferencePaymentConfig` already live.
- `AccessLogService`/`GeoLocationService` live in `org.confcms.cms.service`, alongside the existing `FileStorageService`.
- `AdminAccessLogController` lives in `org.confcms.cms.web.controller`, matching `AdminRegistrationController`'s location.
- An access-log write failure, a GeoLite2 lookup failure, or a content-hash computation failure must NEVER block the login or upload it's associated with — every one of these is wrapped so the primary action always succeeds regardless.
- Bank-slip views are explicitly NOT logged as `PAPER_DOWNLOAD` events — out of scope, a deliberate boundary per the spec.
- Failed-login-attempt logging, admin log filtering/search UI, and retention-enforcement code are explicitly out of scope for this plan.

---

### Task 1: `AccessLog` entity, `AccessEventType`, `AccessLogRepository`

**Files:**
- Create: `src/main/java/org/confcms/cms/domain/AccessEventType.java`
- Create: `src/main/java/org/confcms/cms/domain/AccessLog.java`
- Create: `src/main/java/org/confcms/cms/repository/AccessLogRepository.java`

This task is pure entity/repository schema with no business logic — no dedicated unit test applies (matching this session's established convention for `PaymentStatus`/`Registration` FK additions, which also had no test of their own).

- [ ] **Step 1: Create `AccessEventType`**

Create `src/main/java/org/confcms/cms/domain/AccessEventType.java`:

```java
package org.confcms.cms.domain;

public enum AccessEventType {
    LOGIN,
    PAPER_DOWNLOAD
}
```

- [ ] **Step 2: Create `AccessLog`**

Create `src/main/java/org/confcms/cms/domain/AccessLog.java`:

```java
package org.confcms.cms.domain;

import org.confcms.cms.core.domain.BaseEntity;
import org.confcms.cms.submission.domain.PaperVersion;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "access_logs")
@Getter
@Setter
public class AccessLog extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccessEventType eventType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String ipAddress;

    private String resolvedLocation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_version_id")
    private PaperVersion paperVersion;
}
```

- [ ] **Step 3: Create `AccessLogRepository`**

Create `src/main/java/org/confcms/cms/repository/AccessLogRepository.java`:

```java
package org.confcms.cms.repository;

import org.confcms.cms.domain.AccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {
    List<AccessLog> findTop100ByOrderByCreatedAtDesc();
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/domain/AccessEventType.java src/main/java/org/confcms/cms/domain/AccessLog.java src/main/java/org/confcms/cms/repository/AccessLogRepository.java
git commit -m "feat: add AccessLog entity for login and paper-download audit events"
```

---

### Task 2: `GeoLocationService` + MaxMind dependency + move the `.mmdb` file

**Files:**
- Modify: `build.gradle`
- Create: `src/main/java/org/confcms/cms/service/GeoLocationService.java`
- Test: Create `src/test/java/org/confcms/cms/service/GeoLocationServiceTest.java`
- Modify (move): `src/main/resources/static/GeoLite2-City.mmdb` → `src/main/resources/geoip/GeoLite2-City.mmdb`

- [ ] **Step 1: Add the MaxMind dependency**

Modify `build.gradle` — add this line inside the existing `dependencies { }` block, near the other `implementation(...)` lines:

```gradle
    implementation("com.maxmind.geoip2:geoip2:4.3.0")
```

- [ ] **Step 2: Move the `.mmdb` file out of the public `static/` directory**

Run:
```bash
mkdir -p src/main/resources/geoip
git mv src/main/resources/static/GeoLite2-City.mmdb src/main/resources/geoip/GeoLite2-City.mmdb
```

- [ ] **Step 3: Write the failing test**

Create `src/test/java/org/confcms/cms/service/GeoLocationServiceTest.java`:

```java
package org.confcms.cms.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoLocationServiceTest {

    @Test
    void resolveLocationReturnsEmptyForALoopbackAddress() {
        GeoLocationService service = new GeoLocationService();

        assertThat(service.resolveLocation("127.0.0.1")).isEmpty();
    }

    @Test
    void resolveLocationReturnsEmptyForAPrivateAddress() {
        GeoLocationService service = new GeoLocationService();

        assertThat(service.resolveLocation("192.168.1.1")).isEmpty();
    }

    @Test
    void resolveLocationReturnsEmptyForAnUnresolvableHostname() {
        GeoLocationService service = new GeoLocationService();

        assertThat(service.resolveLocation("not-a-valid-address")).isEmpty();
    }
}
```

(These tests exercise the real bundled `.mmdb` file loaded from the classpath, and rely on it correctly reporting "no location" for private/loopback/invalid addresses — this is the actually-testable subset of `GeoLocationService` without needing MaxMind's separate test-fixture databases for real-world IP lookups; a real public IP's resolved city/country is verified manually in Task 9.)

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.service.GeoLocationServiceTest"`
Expected: FAIL — compile error, `GeoLocationService` does not exist yet.

- [ ] **Step 5: Create `GeoLocationService`**

Create `src/main/java/org/confcms/cms/service/GeoLocationService.java`:

```java
package org.confcms.cms.service;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CityResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Optional;

@Service
public class GeoLocationService {

    private static final Logger log = LoggerFactory.getLogger(GeoLocationService.class);

    private final DatabaseReader reader;

    public GeoLocationService() {
        DatabaseReader loaded = null;
        try (InputStream in = getClass().getResourceAsStream("/geoip/GeoLite2-City.mmdb")) {
            if (in == null) {
                log.warn("GeoLite2-City.mmdb not found on classpath; login location resolution disabled");
            } else {
                loaded = new DatabaseReader.Builder(in).build();
            }
        } catch (IOException e) {
            log.warn("Failed to load GeoLite2-City.mmdb; login location resolution disabled", e);
        }
        this.reader = loaded;
    }

    public Optional<String> resolveLocation(String ipAddress) {
        if (reader == null) {
            return Optional.empty();
        }
        try {
            InetAddress address = InetAddress.getByName(ipAddress);
            CityResponse response = reader.city(address);
            String city = response.getCity().getName();
            String country = response.getCountry().getName();
            if (city == null && country == null) {
                return Optional.empty();
            }
            return Optional.of((city != null ? city + ", " : "") + (country != null ? country : ""));
        } catch (GeoIp2Exception | IOException e) {
            // Includes AddressNotFoundException for private/loopback/unresolvable IPs -- expected
            // and common in local development, not an error worth logging at WARN level.
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.service.GeoLocationServiceTest"`
Expected: PASS, 3 tests.

- [ ] **Step 7: Compile the full project**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add build.gradle src/main/java/org/confcms/cms/service/GeoLocationService.java src/test/java/org/confcms/cms/service/GeoLocationServiceTest.java src/main/resources/geoip/GeoLite2-City.mmdb
git status --short
```

Confirm the `git status --short` output shows the old `src/main/resources/static/GeoLite2-City.mmdb` path as deleted (`D`) and the new `src/main/resources/geoip/GeoLite2-City.mmdb` path as added (`A`) — `git mv` in Step 2 should already have staged this rename, but verify before committing since a missed `git add` on a moved binary file is easy to overlook.

```bash
git commit -m "feat: add GeoLocationService, move GeoLite2 database out of public static dir"
```

---

### Task 3: `AccessLogService`

**Files:**
- Create: `src/main/java/org/confcms/cms/service/AccessLogService.java`
- Test: Create `src/test/java/org/confcms/cms/service/AccessLogServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/confcms/cms/service/AccessLogServiceTest.java`:

```java
package org.confcms.cms.service;

import org.confcms.cms.domain.AccessEventType;
import org.confcms.cms.domain.AccessLog;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.AccessLogRepository;
import org.confcms.cms.submission.domain.PaperVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessLogServiceTest {

    @Mock
    private AccessLogRepository accessLogRepository;
    @Mock
    private GeoLocationService geoLocationService;

    private AccessLogService service;

    @BeforeEach
    void setUp() {
        service = new AccessLogService(accessLogRepository, geoLocationService);
    }

    @Test
    void logLoginWritesALoginEntryWithResolvedLocation() {
        User user = new User();
        user.setId(1L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.5");
        when(geoLocationService.resolveLocation("203.0.113.5")).thenReturn(Optional.of("Bangkok, Thailand"));

        service.logLogin(user, request);

        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(accessLogRepository).save(captor.capture());
        AccessLog saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(AccessEventType.LOGIN);
        assertThat(saved.getUser()).isEqualTo(user);
        assertThat(saved.getIpAddress()).isEqualTo("203.0.113.5");
        assertThat(saved.getResolvedLocation()).isEqualTo("Bangkok, Thailand");
        assertThat(saved.getPaperVersion()).isNull();
    }

    @Test
    void logLoginStoresNullLocationWhenResolutionFails() {
        User user = new User();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        when(geoLocationService.resolveLocation("127.0.0.1")).thenReturn(Optional.empty());

        service.logLogin(user, request);

        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(accessLogRepository).save(captor.capture());
        assertThat(captor.getValue().getResolvedLocation()).isNull();
    }

    @Test
    void logDownloadWritesAPaperDownloadEntryWithTheGivenVersion() {
        User user = new User();
        PaperVersion version = new PaperVersion();
        version.setId(9L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.5");
        when(geoLocationService.resolveLocation("203.0.113.5")).thenReturn(Optional.empty());

        service.logDownload(user, version, request);

        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(accessLogRepository).save(captor.capture());
        AccessLog saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(AccessEventType.PAPER_DOWNLOAD);
        assertThat(saved.getPaperVersion()).isEqualTo(version);
    }

    @Test
    void logLoginDoesNotPropagateARepositorySaveFailure() {
        User user = new User();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.5");
        when(geoLocationService.resolveLocation("203.0.113.5")).thenReturn(Optional.empty());
        doThrow(new RuntimeException("DB unavailable")).when(accessLogRepository).save(any());

        // Must not throw -- an audit-log failure must never break the action being audited.
        service.logLogin(user, request);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.service.AccessLogServiceTest"`
Expected: FAIL — compile error, `AccessLogService` does not exist yet.

- [ ] **Step 3: Create `AccessLogService`**

Create `src/main/java/org/confcms/cms/service/AccessLogService.java`:

```java
package org.confcms.cms.service;

import org.confcms.cms.domain.AccessEventType;
import org.confcms.cms.domain.AccessLog;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.AccessLogRepository;
import org.confcms.cms.submission.domain.PaperVersion;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccessLogService {

    private static final Logger log = LoggerFactory.getLogger(AccessLogService.class);

    private final AccessLogRepository accessLogRepository;
    private final GeoLocationService geoLocationService;

    @Transactional
    public void logLogin(User user, HttpServletRequest request) {
        write(AccessEventType.LOGIN, user, request, null);
    }

    @Transactional
    public void logDownload(User user, PaperVersion paperVersion, HttpServletRequest request) {
        write(AccessEventType.PAPER_DOWNLOAD, user, request, paperVersion);
    }

    private void write(AccessEventType eventType, User user, HttpServletRequest request, PaperVersion paperVersion) {
        try {
            String ip = request.getRemoteAddr();
            AccessLog entry = new AccessLog();
            entry.setEventType(eventType);
            entry.setUser(user);
            entry.setIpAddress(ip);
            entry.setResolvedLocation(geoLocationService.resolveLocation(ip).orElse(null));
            entry.setPaperVersion(paperVersion);
            accessLogRepository.save(entry);
        } catch (Exception e) {
            // An audit-log write failure must never break the login or download it's auditing --
            // losing one log row is an acceptable gap; blocking a real user action to protect an
            // audit trail is not.
            log.warn("Failed to write access log entry (eventType={})", eventType, e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.service.AccessLogServiceTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/service/AccessLogService.java src/test/java/org/confcms/cms/service/AccessLogServiceTest.java
git commit -m "feat: add AccessLogService for writing login and download audit events"
```

---

### Task 4: Wire login logging into `PasswordPromptAuthenticationSuccessHandler`

**Files:**
- Modify: `src/main/java/org/confcms/cms/security/PasswordPromptAuthenticationSuccessHandler.java`
- Modify: `src/test/java/org/confcms/cms/security/PasswordPromptAuthenticationSuccessHandlerTest.java`

- [ ] **Step 1: Update the existing test file for the new constructor parameter and new logging behavior**

Modify `src/test/java/org/confcms/cms/security/PasswordPromptAuthenticationSuccessHandlerTest.java` — replace the full file:

```java
package org.confcms.cms.security;

import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.AccessLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordPromptAuthenticationSuccessHandlerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private AccessLogService accessLogService;

    private PasswordPromptAuthenticationSuccessHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PasswordPromptAuthenticationSuccessHandler(userRepository, accessLogService);
        // Only the login-logging tests care about this lookup; stub leniently so the
        // markPromptIfPasswordless-only tests aren't penalized by Mockito's strict-stubs check.
        lenient().when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
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
    void setsSessionAttributeForUserWithEmptyStringPasswordHash() {
        User emptyHash = new User();
        emptyHash.setEmail("emptyhash@example.com");
        emptyHash.setPasswordHash("");
        when(userRepository.findByEmail("emptyhash@example.com")).thenReturn(Optional.of(emptyHash));

        MockHttpServletRequest request = new MockHttpServletRequest();

        handler.markPromptIfPasswordless(request, "emptyhash@example.com");

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

    @Test
    void onAuthenticationSuccessLogsTheLoginWhenTheUserIsFound() throws Exception {
        User user = new User();
        user.setEmail("author@example.com");
        user.setPasswordHash("hashed");
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(user));

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        var authentication = new UsernamePasswordAuthenticationToken("author@example.com", null, Collections.emptyList());

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(accessLogService).logLogin(user, request);
    }

    @Test
    void onAuthenticationSuccessStillRedirectsWhenUserLookupFindsNothing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        var authentication = new UsernamePasswordAuthenticationToken("ghost@example.com", null, Collections.emptyList());

        handler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).isEqualTo("/dashboard");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.security.PasswordPromptAuthenticationSuccessHandlerTest"`
Expected: FAIL — compile error, constructor doesn't accept a second argument yet.

- [ ] **Step 3: Add the `AccessLogService` dependency and the login-logging call**

Modify `src/main/java/org/confcms/cms/security/PasswordPromptAuthenticationSuccessHandler.java` — replace the full file:

```java
package org.confcms.cms.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.AccessLogService;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class PasswordPromptAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final AccessLogService accessLogService;

    public PasswordPromptAuthenticationSuccessHandler(UserRepository userRepository, AccessLogService accessLogService) {
        this.userRepository = userRepository;
        this.accessLogService = accessLogService;
        setDefaultTargetUrl("/dashboard");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        markPromptIfPasswordless(request, authentication.getName());
        userRepository.findByEmail(authentication.getName())
                .ifPresent(user -> accessLogService.logLogin(user, request));
        super.onAuthenticationSuccess(request, response, authentication);
    }

    void markPromptIfPasswordless(HttpServletRequest request, String email) {
        userRepository.findByEmail(email)
                .filter(user -> user.getPasswordHash() == null || user.getPasswordHash().isBlank())
                .ifPresent(user -> request.getSession().setAttribute("passwordPromptPending", true));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.security.PasswordPromptAuthenticationSuccessHandlerTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Check other construction sites of `PasswordPromptAuthenticationSuccessHandler`**

Run: `grep -rn "new PasswordPromptAuthenticationSuccessHandler(" src/main/java/ src/test/java/`
Expected: no matches outside the test file just updated — this class is `@Component`-managed (Spring constructs it via dependency injection in `SecurityConfig`/`DevSecurityConfig`, which reference the bean by type, not by calling `new` directly). If the grep finds any other manual construction site, stop and report it — it would need the same two-argument update.

- [ ] **Step 6: Compile the full project**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/security/PasswordPromptAuthenticationSuccessHandler.java src/test/java/org/confcms/cms/security/PasswordPromptAuthenticationSuccessHandlerTest.java
git commit -m "feat: log successful logins to the access audit log"
```

---

### Task 5: Wire download logging into `ReviewRestController`

**Files:**
- Modify: `src/main/java/org/confcms/cms/review/service/ReviewService.java`
- Modify: `src/main/java/org/confcms/cms/web/controller/ReviewRestController.java`
- Test: Create `src/test/java/org/confcms/cms/web/controller/ReviewRestControllerTest.java`
- Test: Modify `src/test/java/org/confcms/cms/review/service/ReviewServiceTest.java` (if it references `getLatestVersionFilePath` — confirmed it currently does not, so this is an addition only)

- [ ] **Step 1: Add `getLatestVersion` to `ReviewService`, delegate `getLatestVersionFilePath` to it**

Modify `src/main/java/org/confcms/cms/review/service/ReviewService.java` — replace:

```java
    @Transactional(readOnly = true)
    public String getLatestVersionFilePath(Paper paper) {
        return paper.getVersions().stream()
                .max(Comparator.comparing(PaperVersion::getVersionNumber))
                .map(PaperVersion::getFilePath)
                .orElseThrow(() -> new IllegalArgumentException("Paper has no uploaded version"));
    }
```

with:

```java
    @Transactional(readOnly = true)
    public PaperVersion getLatestVersion(Paper paper) {
        return paper.getVersions().stream()
                .max(Comparator.comparing(PaperVersion::getVersionNumber))
                .orElseThrow(() -> new IllegalArgumentException("Paper has no uploaded version"));
    }

    @Transactional(readOnly = true)
    public String getLatestVersionFilePath(Paper paper) {
        return getLatestVersion(paper).getFilePath();
    }
```

(`getLatestVersionFilePath` is confirmed to have exactly one caller in the codebase, `ReviewRestController.downloadPaperForReview` — grep `getLatestVersionFilePath` across `src/main/` and `src/test/` to confirm this before proceeding, since Step 2 changes that one caller to use `getLatestVersion` instead, but `getLatestVersionFilePath` itself stays for any future/external caller rather than being deleted outright.)

- [ ] **Step 2: Write the failing test for `ReviewRestController`**

No `ReviewRestControllerTest.java` exists (confirmed). Create `src/test/java/org/confcms/cms/web/controller/ReviewRestControllerTest.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;
import org.confcms.cms.review.repository.ReviewRepository;
import org.confcms.cms.review.service.ReviewAssignmentService;
import org.confcms.cms.review.service.ReviewService;
import org.confcms.cms.service.AccessLogService;
import org.confcms.cms.service.FileStorageService;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperVersion;
import org.confcms.cms.submission.repository.PaperRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewRestControllerTest {

    @Mock
    private ReviewAssignmentService assignmentService;
    @Mock
    private ReviewService reviewService;
    @Mock
    private ReviewAssignmentRepository assignmentRepository;
    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PaperRepository paperRepository;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private AccessLogService accessLogService;

    private ReviewRestController controller;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        controller = new ReviewRestController(assignmentService, reviewService, assignmentRepository,
                reviewRepository, userRepository, paperRepository, fileStorageService, accessLogService);
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
    void downloadPaperForReviewLogsTheDownloadOnSuccess() throws Exception {
        User reviewer = new User();
        reviewer.setEmail("reviewer@example.com");
        authenticateAs(reviewer);
        when(userRepository.findByEmail("reviewer@example.com")).thenReturn(Optional.of(reviewer));

        Paper paper = new Paper();
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setPaper(paper);
        when(reviewService.getOwnedAssignment(reviewer, 5L)).thenReturn(assignment);

        PaperVersion version = new PaperVersion();
        version.setId(9L);
        Path pdfFile = tempDir.resolve("paper.pdf");
        Files.write(pdfFile, "%PDF-1.4 fake".getBytes());
        version.setFilePath(pdfFile.toString());
        when(reviewService.getLatestVersion(paper)).thenReturn(version);
        when(fileStorageService.load(pdfFile.toString())).thenReturn(pdfFile);

        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<?> response = controller.downloadPaperForReview(5L, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(accessLogService).logDownload(reviewer, version, request);
    }

    @Test
    void downloadPaperForReviewDoesNotLogOnSecurityFailure() {
        User reviewer = new User();
        reviewer.setEmail("reviewer@example.com");
        authenticateAs(reviewer);
        when(userRepository.findByEmail("reviewer@example.com")).thenReturn(Optional.of(reviewer));
        when(reviewService.getOwnedAssignment(reviewer, 5L)).thenThrow(new SecurityException("Not your assignment"));

        MockHttpServletRequest request = new MockHttpServletRequest();

        ResponseEntity<?> response = controller.downloadPaperForReview(5L, request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(accessLogService, org.mockito.Mockito.never()).logDownload(any(), any(), any());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.ReviewRestControllerTest"`
Expected: FAIL — compile error, `ReviewRestController` constructor doesn't accept an 8th argument yet, and `downloadPaperForReview` doesn't accept an `HttpServletRequest` parameter yet.

- [ ] **Step 4: Wire `AccessLogService` and the download-logging call into `ReviewRestController`**

Modify `src/main/java/org/confcms/cms/web/controller/ReviewRestController.java` — add the import and field:

```java
import org.confcms.cms.service.AccessLogService;
```

```java
    private final org.confcms.cms.service.FileStorageService fileStorageService;
    private final AccessLogService accessLogService;
```

Replace the `downloadPaperForReview` method:

```java
    @GetMapping("/assignment/{assignmentId}/paper/file")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<?> downloadPaperForReview(@PathVariable Long assignmentId, jakarta.servlet.http.HttpServletRequest request) {
        try {
            Paper paper = reviewService.getOwnedAssignment(actingUser(), assignmentId).getPaper();
            org.confcms.cms.submission.domain.PaperVersion version = reviewService.getLatestVersion(paper);
            Resource resource = new UrlResource(fileStorageService.load(version.getFilePath()).toUri());
            accessLogService.logDownload(actingUser(), version, request);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"paper.pdf\"")
                    .body(resource);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.status(404).body(iae.getMessage());
        } catch (java.net.MalformedURLException e) {
            return ResponseEntity.status(404).body("File not found");
        }
    }
```

(`@RequiredArgsConstructor` regenerates the constructor automatically to include the new `accessLogService` field as its final parameter, matching the test's `new ReviewRestController(assignmentService, reviewService, assignmentRepository, reviewRepository, userRepository, paperRepository, fileStorageService, accessLogService)` call order — field declaration order in the class determines constructor parameter order with Lombok's `@RequiredArgsConstructor`, so the new field must be added last, after `fileStorageService`, to match the test.)

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.ReviewRestControllerTest"`
Expected: PASS, 2 tests.

- [ ] **Step 6: Add a test for `ReviewService.getLatestVersion`**

Find `src/test/java/org/confcms/cms/review/service/ReviewServiceTest.java` and read it to match its existing constructor/mock setup pattern, then add:

```java
    @Test
    void getLatestVersionReturnsTheHighestVersionNumber() {
        Paper paper = new Paper();
        PaperVersion v1 = new PaperVersion();
        v1.setVersionNumber(1);
        PaperVersion v2 = new PaperVersion();
        v2.setVersionNumber(2);
        paper.getVersions().add(v1);
        paper.getVersions().add(v2);

        PaperVersion result = service.getLatestVersion(paper);

        assertThat(result).isEqualTo(v2);
    }
```

(Adapt `service` to whatever variable name the existing test file uses for its `ReviewService` instance — read the file first to match exactly; this plan does not replace the whole file since it may have many existing tests worth preserving verbatim.)

- [ ] **Step 7: Run the review-service test file, then compile the full project**

Run: `./gradlew test --tests "org.confcms.cms.review.service.ReviewServiceTest"`
Expected: PASS, all existing tests plus the one new test.

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/confcms/cms/review/service/ReviewService.java src/main/java/org/confcms/cms/web/controller/ReviewRestController.java src/test/java/org/confcms/cms/web/controller/ReviewRestControllerTest.java src/test/java/org/confcms/cms/review/service/ReviewServiceTest.java
git commit -m "feat: log reviewer paper downloads to the access audit log"
```

---

### Task 6: `PaperVersion` gains `contentHash`/`possibleDuplicate`/`duplicateOfPaperVersionId`, `PaperVersionRepository`

**Files:**
- Modify: `src/main/java/org/confcms/cms/submission/domain/PaperVersion.java`
- Create: `src/main/java/org/confcms/cms/submission/repository/PaperVersionRepository.java`

Pure entity/repository schema change, no business logic — no dedicated unit test (matching Task 1's convention).

- [ ] **Step 1: Add the three new fields to `PaperVersion`**

Modify `src/main/java/org/confcms/cms/submission/domain/PaperVersion.java` — replace the full file:

```java
package org.confcms.cms.submission.domain;

import org.confcms.cms.core.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "paper_versions")
@Getter
@Setter
public class PaperVersion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "paper_id", nullable = false)
    private Paper paper;

    @Column(nullable = false)
    private Integer versionNumber;

    @Column(nullable = false)
    private String filePath;

    @Column(nullable = false)
    private String originalFilename;

    @Column(length = 64)
    private String contentHash;

    @Column(nullable = false)
    private boolean possibleDuplicate = false;

    private Long duplicateOfPaperVersionId;
}
```

- [ ] **Step 2: Create `PaperVersionRepository`**

Create `src/main/java/org/confcms/cms/submission/repository/PaperVersionRepository.java`:

```java
package org.confcms.cms.submission.repository;

import org.confcms.cms.submission.domain.PaperVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaperVersionRepository extends JpaRepository<PaperVersion, Long> {
    List<PaperVersion> findByContentHash(String contentHash);
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/confcms/cms/submission/domain/PaperVersion.java src/main/java/org/confcms/cms/submission/repository/PaperVersionRepository.java
git commit -m "feat: add content-hash and duplicate-flag fields to PaperVersion"
```

---

### Task 7: Wire content-hashing and duplicate detection into `SubmissionService`

**Files:**
- Modify: `src/main/java/org/confcms/cms/submission/service/SubmissionService.java`
- Modify: `src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java`

This is the largest task in this plan — `SubmissionService` gains a new constructor parameter (`PaperVersionRepository`), which changes every one of the 10 existing test call sites, plus new hashing/duplicate-detection logic in all three upload paths.

- [ ] **Step 1: Update the existing test file for the new constructor parameter**

Modify `src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java`:

Add the import:

```java
import org.confcms.cms.submission.repository.PaperVersionRepository;
```

Add the mock field, alongside the existing `@Mock` fields:

```java
    @Mock
    private PaperVersionRepository paperVersionRepository;
```

Then replace every occurrence of this exact string (it appears 10 times, once per `@Test` method, always as the first line of the method body):

```java
SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);
```

with:

```java
SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);
```

Use a project-wide find-and-replace across just this one file for this exact string — it is character-for-character identical at all 10 sites (confirmed by reading the full file), so a single `replace_all`-style edit (old string → new string, matching all occurrences in this one file) is safe and correct here.

Additionally, each test's `when(paperRepository.save(any())).thenAnswer(...)` stub will now execute against upload code that also calls `paperVersionRepository.findByContentHash(...)` — since none of the existing 10 tests stub that method, Mockito's `@Mock` default for an unstubbed method returning `List<PaperVersion>` is an empty list (Mockito's `RETURNS_DEFAULTS` answer for a `List`-returning method returns `Collections.emptyList()`, not `null` — confirmed as a real Mockito default, not an assumption), so no additional stubbing is needed in any of the 10 existing tests for them to keep passing. Do NOT add speculative stubs to the existing tests; only the new tests in Step 6 stub `findByContentHash` explicitly.

- [ ] **Step 2: Run the existing test file to verify it fails to compile**

Run: `./gradlew test --tests "org.confcms.cms.submission.service.SubmissionServiceTest"`
Expected: FAIL — compile error, `SubmissionService`'s constructor doesn't accept a 7th argument yet.

- [ ] **Step 3: Add the hashing helper and wire it into all three upload methods**

Modify `src/main/java/org/confcms/cms/submission/service/SubmissionService.java` — add these imports:

```java
import org.confcms.cms.submission.repository.PaperVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.util.HexFormat;
```

Add the new field and logger, and add `PaperVersionRepository` as the constructor's final parameter (via the existing `@RequiredArgsConstructor` + field-declaration-order mechanism, matching Task 5's approach):

```java
    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

    private final PaperVersionRepository paperVersionRepository;
```

(Add this field declaration last, after the existing `private final UserRepository userRepository;` line, so `@RequiredArgsConstructor`'s generated constructor parameter order matches Step 1's test call: `(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository)`.)

Add the new private helper method, near `validatePdf`:

```java
    private String computeContentHash(MultipartFile file) {
        try (var in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            log.warn("Failed to compute content hash for uploaded file; duplicate detection skipped for this upload", e);
            return null;
        }
    }

    private void applyDuplicateCheck(PaperVersion version, String contentHash) {
        version.setContentHash(contentHash);
        if (contentHash == null) {
            return;
        }
        List<PaperVersion> matches = paperVersionRepository.findByContentHash(contentHash);
        if (!matches.isEmpty()) {
            version.setPossibleDuplicate(true);
            version.setDuplicateOfPaperVersionId(matches.get(0).getId());
        }
    }
```

Modify `submitPaper` — replace:

```java
        // Validate and save file
        validatePdf(file);
        String filePath = fileStorageService.store(file);

        PaperVersion version = new PaperVersion();
        version.setPaper(paper);
        version.setVersionNumber(1);
        version.setFilePath(filePath);
        version.setOriginalFilename(file.getOriginalFilename());
        paper.getVersions().add(version);
```

with:

```java
        // Validate and save file
        validatePdf(file);
        String contentHash = computeContentHash(file);
        String filePath = fileStorageService.store(file);

        PaperVersion version = new PaperVersion();
        version.setPaper(paper);
        version.setVersionNumber(1);
        version.setFilePath(filePath);
        version.setOriginalFilename(file.getOriginalFilename());
        applyDuplicateCheck(version, contentHash);
        paper.getVersions().add(version);
```

Modify `uploadNewVersion` — replace:

```java
        validatePdf(file);
        String filePath = fileStorageService.store(file);

        int newVersionNumber = paper.getVersions().size() + 1;
        PaperVersion version = new PaperVersion();
        version.setPaper(paper);
        version.setVersionNumber(newVersionNumber);
        version.setFilePath(filePath);
        version.setOriginalFilename(file.getOriginalFilename());
        paper.getVersions().add(version);
        Paper saved = paperRepository.save(paper);
```

with:

```java
        validatePdf(file);
        String contentHash = computeContentHash(file);
        String filePath = fileStorageService.store(file);

        int newVersionNumber = paper.getVersions().size() + 1;
        PaperVersion version = new PaperVersion();
        version.setPaper(paper);
        version.setVersionNumber(newVersionNumber);
        version.setFilePath(filePath);
        version.setOriginalFilename(file.getOriginalFilename());
        applyDuplicateCheck(version, contentHash);
        paper.getVersions().add(version);
        Paper saved = paperRepository.save(paper);
```

Modify `uploadRevision` — replace:

```java
        validatePdf(file);
        String filePath = fileStorageService.store(file);

        int newVersionNumber = paper.getVersions().size() + 1;
        PaperVersion version = new PaperVersion();
        version.setPaper(paper);
        version.setVersionNumber(newVersionNumber);
        version.setFilePath(filePath);
        version.setOriginalFilename(file.getOriginalFilename());
        paper.getVersions().add(version);

        return paperRepository.save(paper);
```

with:

```java
        validatePdf(file);
        String contentHash = computeContentHash(file);
        String filePath = fileStorageService.store(file);

        int newVersionNumber = paper.getVersions().size() + 1;
        PaperVersion version = new PaperVersion();
        version.setPaper(paper);
        version.setVersionNumber(newVersionNumber);
        version.setFilePath(filePath);
        version.setOriginalFilename(file.getOriginalFilename());
        applyDuplicateCheck(version, contentHash);
        paper.getVersions().add(version);

        return paperRepository.save(paper);
```

- [ ] **Step 4: Run the existing test file to verify all 10 pre-existing tests still pass**

Run: `./gradlew test --tests "org.confcms.cms.submission.service.SubmissionServiceTest"`
Expected: PASS, 10 tests (the pre-existing ones, unaffected by the additive change since none of them stub `findByContentHash`, so it returns an empty list and no duplicate is flagged).

- [ ] **Step 5: Write new failing tests for the duplicate-detection behavior**

Add to `src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java`, as new `@Test` methods:

```java
    @Test
    void submitPaperFlagsPossibleDuplicateWhenContentHashMatches() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

        Conference activeConference = new Conference();
        activeConference.setTitle("Test Conf");
        activeConference.setVenue("Test Venue");
        activeConference.setStartDate(LocalDate.now());
        activeConference.setEndDate(LocalDate.now().plusDays(1));
        when(conferenceService.getActiveConference()).thenReturn(activeConference);

        User submitter = new User();
        submitter.setFullName("Jane Author");
        submitter.setEmail("jane@example.com");

        when(fileStorageService.store(any())).thenReturn("/uploads/paper.pdf");
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaperVersion existingMatch = new PaperVersion();
        existingMatch.setId(42L);
        when(paperVersionRepository.findByContentHash(any())).thenReturn(List.of(existingMatch));

        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "%PDF-1.4 identical content".getBytes());

        Paper saved = service.submitPaper(submitter, "Title", "Abstract", "Track A", file, Collections.emptyList());

        PaperVersion newVersion = saved.getVersions().get(0);
        assertThat(newVersion.isPossibleDuplicate()).isTrue();
        assertThat(newVersion.getDuplicateOfPaperVersionId()).isEqualTo(42L);
        assertThat(newVersion.getContentHash()).isNotNull();
    }

    @Test
    void submitPaperDoesNotFlagDuplicateWhenNoHashMatchExists() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

        Conference activeConference = new Conference();
        activeConference.setTitle("Test Conf");
        activeConference.setVenue("Test Venue");
        activeConference.setStartDate(LocalDate.now());
        activeConference.setEndDate(LocalDate.now().plusDays(1));
        when(conferenceService.getActiveConference()).thenReturn(activeConference);

        User submitter = new User();
        submitter.setFullName("Jane Author");
        submitter.setEmail("jane@example.com");

        when(fileStorageService.store(any())).thenReturn("/uploads/paper.pdf");
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paperVersionRepository.findByContentHash(any())).thenReturn(List.of());

        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "%PDF-1.4 unique content".getBytes());

        Paper saved = service.submitPaper(submitter, "Title", "Abstract", "Track A", file, Collections.emptyList());

        PaperVersion newVersion = saved.getVersions().get(0);
        assertThat(newVersion.isPossibleDuplicate()).isFalse();
        assertThat(newVersion.getDuplicateOfPaperVersionId()).isNull();
        assertThat(newVersion.getContentHash()).isNotNull();
    }

    @Test
    void submitPaperComputesTheSameHashForIdenticalContent() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

        Conference activeConference = new Conference();
        activeConference.setTitle("Test Conf");
        activeConference.setVenue("Test Venue");
        activeConference.setStartDate(LocalDate.now());
        activeConference.setEndDate(LocalDate.now().plusDays(1));
        when(conferenceService.getActiveConference()).thenReturn(activeConference);

        User submitter = new User();
        submitter.setFullName("Jane Author");
        submitter.setEmail("jane@example.com");

        when(fileStorageService.store(any())).thenReturn("/uploads/a.pdf", "/uploads/b.pdf");
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paperVersionRepository.findByContentHash(any())).thenReturn(List.of());

        byte[] identicalBytes = "%PDF-1.4 identical bytes for hash comparison".getBytes();
        MockMultipartFile fileA = new MockMultipartFile("file", "a.pdf", "application/pdf", identicalBytes);
        MockMultipartFile fileB = new MockMultipartFile("file", "b.pdf", "application/pdf", identicalBytes);

        Paper savedA = service.submitPaper(submitter, "Title A", "Abstract", "Track A", fileA, Collections.emptyList());
        Paper savedB = service.submitPaper(submitter, "Title B", "Abstract", "Track A", fileB, Collections.emptyList());

        assertThat(savedA.getVersions().get(0).getContentHash())
                .isEqualTo(savedB.getVersions().get(0).getContentHash());
    }
```

- [ ] **Step 6: Run test to verify the new tests pass**

Run: `./gradlew test --tests "org.confcms.cms.submission.service.SubmissionServiceTest"`
Expected: PASS, 13 tests (10 pre-existing + 3 new).

- [ ] **Step 7: Compile the full project**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/confcms/cms/submission/service/SubmissionService.java src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java
git commit -m "feat: hash uploaded papers and flag byte-identical duplicate submissions"
```

---

### Task 8: Admin UI — duplicate badge on the papers list, new access-log page

**Files:**
- Modify: `src/main/java/org/confcms/cms/admin/controller/AdminController.java`
- Modify: `src/test/java/org/confcms/cms/admin/controller/AdminControllerTest.java`
- Modify: `src/main/resources/templates/admin/dashboard.html`
- Create: `src/main/java/org/confcms/cms/web/controller/AdminAccessLogController.java`
- Test: Create `src/test/java/org/confcms/cms/web/controller/AdminAccessLogControllerTest.java`
- Create: `src/main/resources/templates/admin/access_logs.html`

- [ ] **Step 1: Write the failing test for the duplicate-match enrichment on `AdminController`**

Modify `src/test/java/org/confcms/cms/admin/controller/AdminControllerTest.java` — replace the full file:

```java
package org.confcms.cms.admin.controller;

import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperVersion;
import org.confcms.cms.submission.repository.PaperVersionRepository;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private SubmissionService submissionService;
    @Mock
    private PaperVersionRepository paperVersionRepository;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(submissionService, paperVersionRepository);
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

    @Test
    void dashboardHasNoDuplicateMatchesWhenNoPaperIsFlagged() {
        Paper paper = new Paper();
        paper.setId(1L);
        PaperVersion version = new PaperVersion();
        version.setPossibleDuplicate(false);
        paper.getVersions().add(version);
        when(submissionService.getAllPapers()).thenReturn(List.of(paper));
        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        controller.dashboard(request, model);

        @SuppressWarnings("unchecked")
        Map<Long, PaperVersion> matches = (Map<Long, PaperVersion>) model.getAttribute("duplicateMatches");
        assertThat(matches).isEmpty();
    }

    @Test
    void dashboardResolvesTheMatchedVersionForAFlaggedPaper() {
        Paper paper = new Paper();
        paper.setId(1L);
        PaperVersion version = new PaperVersion();
        version.setPossibleDuplicate(true);
        version.setDuplicateOfPaperVersionId(42L);
        paper.getVersions().add(version);
        when(submissionService.getAllPapers()).thenReturn(List.of(paper));

        PaperVersion matchedVersion = new PaperVersion();
        matchedVersion.setId(42L);
        when(paperVersionRepository.findById(42L)).thenReturn(Optional.of(matchedVersion));

        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        controller.dashboard(request, model);

        @SuppressWarnings("unchecked")
        Map<Long, PaperVersion> matches = (Map<Long, PaperVersion>) model.getAttribute("duplicateMatches");
        assertThat(matches).containsEntry(1L, matchedVersion);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.admin.controller.AdminControllerTest"`
Expected: FAIL — compile error, `AdminController`'s constructor doesn't accept a second argument yet.

- [ ] **Step 3: Add `PaperVersionRepository` and the duplicate-match enrichment to `AdminController`**

Modify `src/main/java/org/confcms/cms/admin/controller/AdminController.java` — replace the full file:

```java
package org.confcms.cms.admin.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperVersion;
import org.confcms.cms.submission.repository.PaperVersionRepository;
import org.confcms.cms.submission.service.SubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final SubmissionService submissionService;
    private final PaperVersionRepository paperVersionRepository;

    @GetMapping("/dashboard")
    public String dashboard(HttpServletRequest request, Model model) {
        List<Paper> papers = submissionService.getAllPapers();
        model.addAttribute("papers", papers);
        model.addAttribute("duplicateMatches", resolveDuplicateMatches(papers));
        var session = request.getSession(false);
        model.addAttribute("passwordPromptPending", session != null && session.getAttribute("passwordPromptPending") != null);
        return "admin/dashboard";
    }

    private Map<Long, PaperVersion> resolveDuplicateMatches(List<Paper> papers) {
        Map<Long, PaperVersion> matches = new HashMap<>();
        for (Paper paper : papers) {
            paper.getVersions().stream()
                    .max(Comparator.comparing(PaperVersion::getVersionNumber))
                    .filter(PaperVersion::isPossibleDuplicate)
                    .ifPresent(latest -> paperVersionRepository.findById(latest.getDuplicateOfPaperVersionId())
                            .ifPresent(matched -> matches.put(paper.getId(), matched)));
        }
        return matches;
    }

    @GetMapping("/papers")
    public String papers(Model model) {
        model.addAttribute("papers", submissionService.getAllPapers());
        return "admin/papers";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.admin.controller.AdminControllerTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Add the duplicate badge to `admin/dashboard.html`**

Modify `src/main/resources/templates/admin/dashboard.html` — replace:

```html
                <tr th:each="paper : ${papers}">
                    <td th:text="${paper.id}">1</td>
                    <td th:text="${paper.title}">Title</td>
                    <td th:text="${paper.submitter.fullName}">Author</td>
                    <td>
                        <span class="badge bg-secondary" th:text="${paper.status}">SUBMITTED</span>
                    </td>
                    <td>
                        <a href="#" class="btn btn-sm btn-primary">View</a>
                        <a href="#" class="btn btn-sm btn-info">Assign Reviewer</a>
                    </td>
                </tr>
```

with:

```html
                <tr th:each="paper : ${papers}">
                    <td th:text="${paper.id}">1</td>
                    <td>
                        <span th:text="${paper.title}">Title</span>
                        <span th:if="${duplicateMatches.containsKey(paper.id)}"
                              class="badge bg-warning text-dark"
                              th:title="'Possible duplicate of: ' + ${duplicateMatches.get(paper.id).paper.title} + ' (v' + ${duplicateMatches.get(paper.id).versionNumber} + ')'">
                            &#9888; Possible duplicate
                        </span>
                    </td>
                    <td th:text="${paper.submitter.fullName}">Author</td>
                    <td>
                        <span class="badge bg-secondary" th:text="${paper.status}">SUBMITTED</span>
                    </td>
                    <td>
                        <a href="#" class="btn btn-sm btn-primary">View</a>
                        <a href="#" class="btn btn-sm btn-info">Assign Reviewer</a>
                    </td>
                </tr>
```

- [ ] **Step 6: Add the nav link to the access-log page**

Modify `src/main/resources/templates/admin/dashboard.html` line 14 — replace:

```html
            <a class="btn btn-outline-light me-2" href="/admin/registrations">Registrations</a>
```

with:

```html
            <a class="btn btn-outline-light me-2" href="/admin/registrations">Registrations</a>
            <a class="btn btn-outline-light me-2" href="/admin/access-logs">Access Logs</a>
```

- [ ] **Step 7: Write the failing test for `AdminAccessLogController`**

Create `src/test/java/org/confcms/cms/web/controller/AdminAccessLogControllerTest.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.domain.AccessLog;
import org.confcms.cms.repository.AccessLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAccessLogControllerTest {

    @Mock
    private AccessLogRepository accessLogRepository;

    private AdminAccessLogController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminAccessLogController(accessLogRepository);
    }

    @Test
    void listReturnsTheAccessLogsView() {
        List<AccessLog> logs = List.of(new AccessLog());
        when(accessLogRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(logs);

        Model model = new ExtendedModelMap();
        String view = controller.list(model);

        assertThat(view).isEqualTo("admin/access_logs");
        assertThat(model.getAttribute("logs")).isEqualTo(logs);
    }
}
```

- [ ] **Step 8: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.AdminAccessLogControllerTest"`
Expected: FAIL — compile error, `AdminAccessLogController` does not exist yet.

- [ ] **Step 9: Create `AdminAccessLogController`**

Create `src/main/java/org/confcms/cms/web/controller/AdminAccessLogController.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.repository.AccessLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/access-logs")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAccessLogController {

    private final AccessLogRepository accessLogRepository;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("logs", accessLogRepository.findTop100ByOrderByCreatedAtDesc());
        return "admin/access_logs";
    }
}
```

- [ ] **Step 10: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.AdminAccessLogControllerTest"`
Expected: PASS, 1 test.

- [ ] **Step 11: Create the access-log template**

Create `src/main/resources/templates/admin/access_logs.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head>
    <meta charset="UTF-8">
    <title>Access Logs - Conference CMS</title>
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
        <h2>Access Logs (most recent 100)</h2>
        <table class="table table-striped">
            <thead>
                <tr>
                    <th>Event</th>
                    <th>User</th>
                    <th>Timestamp</th>
                    <th>IP Address</th>
                    <th>Location</th>
                    <th>Paper</th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="entry : ${logs}">
                    <td th:text="${entry.eventType}"></td>
                    <td th:text="${entry.user != null ? entry.user.email : '-'}"></td>
                    <td th:text="${entry.createdAt}"></td>
                    <td th:text="${entry.ipAddress}"></td>
                    <td th:text="${entry.resolvedLocation != null ? entry.resolvedLocation : '-'}"></td>
                    <td th:text="${entry.paperVersion != null ? (entry.paperVersion.paper.title + ' (v' + entry.paperVersion.versionNumber + ')') : '-'}"></td>
                </tr>
                <tr th:if="${logs.isEmpty()}">
                    <td colspan="6" class="text-muted">No access log entries yet.</td>
                </tr>
            </tbody>
        </table>
    </div>
</body>

</html>
```

- [ ] **Step 12: Compile the full project**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 13: Commit**

```bash
git add src/main/java/org/confcms/cms/admin/controller/AdminController.java src/test/java/org/confcms/cms/admin/controller/AdminControllerTest.java src/main/resources/templates/admin/dashboard.html src/main/java/org/confcms/cms/web/controller/AdminAccessLogController.java src/test/java/org/confcms/cms/web/controller/AdminAccessLogControllerTest.java src/main/resources/templates/admin/access_logs.html
git commit -m "feat: add admin access-log page and duplicate-submission badge"
```

---

### Task 9: README documentation + final verification

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add retention and MaxMind-staleness notes to README's Security & Deployment section**

Modify `README.md` — find the existing "Security & Deployment" section (added during this session's PDPA baseline work) and add two new bullets:

```markdown
*   **Access log retention**: this application records every successful login (with IP address
    and, if resolvable, an approximate city/country) and every reviewer paper download in an
    `access_logs` table for audit purposes. Both the IP address and resolved location are
    personal data under PDPA -- self-hosting institutions should periodically purge old rows
    per their own retention policy (e.g. 90 days). The application does not do this
    automatically.
*   **GeoLite2 database staleness**: the bundled `GeoLite2-City.mmdb` (used for the login
    location feature above) will go stale over time. MaxMind's distribution terms require a
    (free) MaxMind account to download updates -- self-hosting institutions should periodically
    replace `src/main/resources/geoip/GeoLite2-City.mmdb` with a current copy.
```

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, zero failures.

- [ ] **Step 3: Boot the dev profile and manually verify the full flow**

Run: `./gradlew bootRun` with the dev profile active. In a browser:
1. Log in as any dev user — confirm no error, dashboard loads normally.
2. As admin, visit `/admin/access-logs` — confirm the login you just performed appears in the list, with a resolved location (or `-` if running from `localhost`, which is expected — loopback addresses have no real-world location).
3. As a reviewer with an assigned paper, download the paper for review — confirm the download succeeds and a new `PAPER_DOWNLOAD` row appears in `/admin/access-logs` with the correct paper title/version.
4. As an author, submit a paper, then submit the exact same PDF file again as a second, unrelated paper (or a new version) — confirm the second submission's row in `/admin/dashboard` shows the "Possible duplicate" badge, and hovering it shows which paper/version it matches.
5. Confirm `/admin/access-logs` and `/admin/dashboard`'s new badge are both correctly blocked for a non-admin user (redirect to `/login` or a 403, consistent with every other `@PreAuthorize("hasRole('ADMIN')")` page in this codebase).

Stop the app once verified.

- [ ] **Step 4: Report completion**

Summarize: all 9 tasks complete, full test suite green, manual verification results from Step 3 above (including whether a resolvable public IP's location was actually confirmed, or only the expected-empty localhost case, given this environment's typical lack of a real public-IP request path).
