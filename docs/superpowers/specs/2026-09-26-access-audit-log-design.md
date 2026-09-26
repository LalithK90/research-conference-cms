# Access Audit Log, Duplicate-Paper Detection & GeoLite2 — Design Spec

## 1. Scope

Roadmap item #8. Three related capabilities, built together around one shared log table, per the gap-analysis doc's own reasoning that login events and file-download events are structurally the same kind of record ("who, when, from where, what"):

1. **Access audit log**: record every successful login and every paper download/view (reviewer download, admin bank-slip view is a separate document type and explicitly NOT included here — see Section 6) in one `AccessLog` table.
2. **Duplicate-paper detection**: SHA-256 hash every uploaded paper PDF; flag (never block) a new submission when its hash matches any prior `PaperVersion` across all conferences on this installation.
3. **GeoLite2 login-location wiring**: the already-bundled but completely unwired `GeoLite2-City.mmdb` resolves an approximate location for every logged login.

**Explicitly out of scope, per this session's clarifying answers:**
- Failed-login-attempt logging (successful logins only, for now).
- Any filter/search UI on the admin log page — a simple capped chronological list only.
- Retention *enforcement* (a scheduled cleanup job) — retention is documented as a policy statement only, consistent with the PDPA baseline spec's own established boundary between "technical baseline" and "consent/retention/deletion workflows," which that spec explicitly deferred.
- Bank-slip views (`AdminRegistrationController.viewSlip`) are not logged as `PAPER_DOWNLOAD` events — that's a different document type (payment proof, not a paper) and adding it would conflate two audit concerns; if a bank-slip access log is wanted later, it deserves its own event type, not misuse of `PAPER_DOWNLOAD`.
- Roadmap item #12 (Proceedings + Zenodo deposit) — dropped from the roadmap entirely per explicit instruction this session; not referenced further in this spec or any future one.

## 2. `AccessLog` entity + `AccessLogService`

### 2.1 Entity

New `src/main/java/org/confcms/cms/domain/AccessLog.java`:

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

`user` is nullable at the column level (a future failed-login-attempt feature might log against an unresolved email with no `User` row), but every event this spec actually writes always has a real, authenticated user — this spec never inserts a null-user row. `paperVersion` is null for `LOGIN` events, set for `PAPER_DOWNLOAD` events. `occurred_at`/`created_at` comes from `BaseEntity`'s existing `@CreatedDate` — no separate timestamp field needed, consistent with how every other entity in this codebase already gets its creation time.

New enum `src/main/java/org/confcms/cms/domain/AccessEventType.java`:

```java
package org.confcms.cms.domain;

public enum AccessEventType {
    LOGIN,
    PAPER_DOWNLOAD
}
```

### 2.2 Repository

New `src/main/java/org/confcms/cms/repository/AccessLogRepository.java`:

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

Matches this session's established preference (from the admin registrations page) for a simple capped list over introducing `Pageable`/pagination UI for a first pass.

### 2.3 `AccessLogService`

New `src/main/java/org/confcms/cms/service/AccessLogService.java`:

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

### 2.4 Wiring into login

Modify `src/main/java/org/confcms/cms/security/PasswordPromptAuthenticationSuccessHandler.java` — this is already the single shared success path for form login, OAuth2 login, and (called directly) magic link, confirmed during this session's PDPA baseline work. Confirmed current file contents: the constructor already takes `UserRepository userRepository`; `markPromptIfPasswordless(HttpServletRequest, String email)` is package-visible and does its own internal `findByEmail` lookup, and is also called directly by `MagicLinkAuthenticationFilter` with just an email string — its signature is not changed by this spec, to avoid rippling into that second caller.

Add the `AccessLogService` dependency and a second, independent `findByEmail` lookup for the new logging call (accepted as a small, deliberate duplication rather than refactoring `markPromptIfPasswordless`'s signature):

```java
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        markPromptIfPasswordless(request, authentication.getName());
        userRepository.findByEmail(authentication.getName())
                .ifPresent(user -> accessLogService.logLogin(user, request));
        super.onAuthenticationSuccess(request, response, authentication);
    }
```

### 2.5 Wiring into paper downloads

**Reviewer download** (`ReviewRestController.downloadPaperForReview`): `ReviewService` gains a new method, `getLatestVersion(Paper paper) -> PaperVersion` (returning the entity, not just its `filePath` string — `getLatestVersionFilePath` stays as-is for backward compatibility with any other caller, though a grep confirms it currently has exactly one caller, this same method, so the plan may choose to have `getLatestVersion` be the single implementation and have `getLatestVersionFilePath` delegate to it):

```java
    @Transactional(readOnly = true)
    public PaperVersion getLatestVersion(Paper paper) {
        return paper.getVersions().stream()
                .max(Comparator.comparing(PaperVersion::getVersionNumber))
                .orElseThrow(() -> new IllegalArgumentException("Paper has no uploaded version"));
    }
```

`ReviewRestController.downloadPaperForReview` changes to call this, then logs after a successful load:

```java
    @GetMapping("/assignment/{assignmentId}/paper/file")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<?> downloadPaperForReview(@PathVariable Long assignmentId, HttpServletRequest request) {
        try {
            Paper paper = reviewService.getOwnedAssignment(actingUser(), assignmentId).getPaper();
            PaperVersion version = reviewService.getLatestVersion(paper);
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

(`HttpServletRequest request` added as a new controller-method parameter — Spring MVC injects it automatically, no new wiring needed. `AccessLogService` added as a new constructor dependency on `ReviewRestController`.)

## 3. Duplicate-paper detection

### 3.1 `PaperVersion` gains `contentHash`, `possibleDuplicate`, `duplicateOfPaperVersionId`

Modify `src/main/java/org/confcms/cms/submission/domain/PaperVersion.java`:

```java
    @Column(length = 64)
    private String contentHash;

    @Column(nullable = false)
    private boolean possibleDuplicate = false;

    private Long duplicateOfPaperVersionId;
```

`contentHash` is nullable — a hashing failure (Section 3.3) is rare and non-fatal, and the column must tolerate it rather than force a fallback sentinel value that would look like a real hash. No backfill for pre-existing rows is attempted; every version from this point forward gets a real hash unless computation genuinely fails, consistent with this session's established pattern of not retrofitting historical data for a new column unless a real migration need exists. `duplicateOfPaperVersionId` is a plain `Long`, not a `@ManyToOne`, deliberately — it's a lightweight cross-reference for display purposes only (the admin UI shows "possible match: Paper X, version Y"), not a real relationship the domain model needs to navigate or cascade through.

### 3.2 `PaperVersionRepository` (new)

No repository exists yet for `PaperVersion` — it's currently only accessed via `Paper.getVersions()`. New `src/main/java/org/confcms/cms/submission/repository/PaperVersionRepository.java`:

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

### 3.3 Hashing + duplicate check in `SubmissionService`

New private helper, and a new `PaperVersionRepository` dependency:

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
```

(`java.security.MessageDigest`, `java.util.HexFormat` imports added; `org.slf4j.Logger log = LoggerFactory.getLogger(SubmissionService.class)` added as a new field — `SubmissionService` currently has no logger.) `HexFormat` is a JDK 17+ standard-library class (this project targets Java 21, confirmed), so no new dependency is needed for hex encoding.

If `computeContentHash` returns `null` (hashing failed), the version is saved with `contentHash = null` — the column is nullable (Section 3.1) specifically to tolerate this. `possibleDuplicate` stays `false` when `contentHash` is null (nothing to compare).

Wired into all three of `SubmissionService`'s upload call sites (`submitPaper`, `uploadNewVersion`, `uploadRevision`), right after `validatePdf(file)` passes, before `fileStorageService.store(file)` (so the hash is computed from the same validated stream state — implementation note: `MultipartFile.getInputStream()` can be called multiple times per Servlet spec for a standard multipart file, confirmed safe to call once for `validatePdf`'s peek and again for hashing):

```java
        validatePdf(file);
        String contentHash = computeContentHash(file);
        String filePath = fileStorageService.store(file);

        PaperVersion version = new PaperVersion();
        version.setPaper(paper); // or the existing paper reference in uploadNewVersion/uploadRevision
        version.setVersionNumber(1); // or the existing version-number logic
        version.setFilePath(filePath);
        version.setOriginalFilename(file.getOriginalFilename());
        version.setContentHash(contentHash);

        if (contentHash != null) {
            List<PaperVersion> matches = paperVersionRepository.findByContentHash(contentHash);
            if (!matches.isEmpty()) {
                version.setPossibleDuplicate(true);
                version.setDuplicateOfPaperVersionId(matches.get(0).getId());
            }
        }
```

(Exact insertion point and surrounding variable names adapted per call site at implementation time — the plan will show the complete, exact diff for each of the three methods individually, not this generic sketch.)

### 3.4 Chair-facing duplicate indicator

The existing admin papers list (`admin/dashboard.html`, rendering `AdminController.dashboard()`'s `papers` model attribute) gains a badge when a paper's latest version has `possibleDuplicate == true`: a small warning badge ("⚠ Possible duplicate") next to the paper title. `duplicateOfPaperVersionId` alone (a plain `Long`) isn't enough for the template to show *what* it duplicates, so `AdminController.dashboard()` resolves it: for each paper whose latest version is flagged, look up the matched `PaperVersion` via the new `PaperVersionRepository.findById(duplicateOfPaperVersionId)` and add a small `Map<Long, PaperVersion> duplicateMatches` (keyed by paper ID) to the model. The template shows the badge as a link to the matched paper's title/version when a match is present in that map — a chair sees not just "possible duplicate" but "possible duplicate of: <Paper Title>, v<N>".

## 4. GeoLite2 wiring

### 4.1 Move the `.mmdb` file, add the dependency

`src/main/resources/static/GeoLite2-City.mmdb` moves to `src/main/resources/geoip/GeoLite2-City.mmdb` (out of the publicly-web-servable `static/` directory).

Modify `build.gradle`, add to the `dependencies` block:

```gradle
    implementation("com.maxmind.geoip2:geoip2:4.2.1")
```

(Exact version confirmed against Maven Central at plan-writing/implementation time, not assumed stale from this design pass.)

### 4.2 `GeoLocationService`

New `src/main/java/org/confcms/cms/service/GeoLocationService.java`:

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

`DatabaseReader` is documented by MaxMind as thread-safe for concurrent `city()` lookups after construction, so a single `@Service`-scoped (effectively singleton) instance is correct — no per-request instantiation needed.

## 5. Admin access-log page

New `src/main/java/org/confcms/cms/web/controller/AdminAccessLogController.java`:

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

New template `src/main/resources/templates/admin/access_logs.html`, same table shape as `admin/registrations.html`: columns for event type, user (email), timestamp, IP address, resolved location, and (only for `PAPER_DOWNLOAD` rows) the paper title/version via `log.paperVersion.paper.title` / `log.paperVersion.versionNumber` (Thymeleaf navigates the lazy association within the transactional view — consistent with how `admin/dashboard.html` already navigates `paper.submitter.fullName` today, confirmed the same `spring.jpa.open-in-view` default this codebase already relies on elsewhere).

`admin/dashboard.html` gains a nav link to `/admin/access-logs`, alongside the existing "Registrations" link (exact placement: same nav bar, one more `<a>` tag).

## 6. Error handling & edge cases

- **Access-log write failure**: never blocks the action being audited (Section 2.3's try/catch).
- **GeoLite2 missing/corrupt file, or unresolvable IP**: `resolveLocation` returns `Optional.empty()`, stored as `null` — never an exception, never blocks login (Section 4.2).
- **Content-hash computation failure**: `possibleDuplicate` stays `false`, upload proceeds normally (Section 3.3).
- **Duplicate badge with no navigable target**: resolved by adding a small enrichment step so the admin template can show the matched paper's identity, not just a raw ID (Section 3.4) — this is the one place this design explicitly corrected itself mid-writing rather than leaving a placeholder.
- **Bank-slip views are NOT logged as `PAPER_DOWNLOAD`** (Section 1) — a deliberate scope boundary, not an oversight, to keep the event type's meaning unambiguous.
- **Reverse-proxy IP resolution**: `request.getRemoteAddr()` returns the proxy's IP, not the real client IP, in any reverse-proxied deployment (nginx, Caddy, a cloud load balancer — all already documented as required for TLS termination in this session's PDPA baseline work). This is a known, real limitation for production deployments behind a reverse proxy; the fix (trusting `X-Forwarded-For` from a configured, trusted proxy) is a deployment-configuration concern (`server.forward-headers-strategy=framework` in Spring Boot, plus reverse-proxy configuration to set the header correctly and strip any client-supplied one) rather than application code this spec needs to write — documented as a deployment note in README, matching the existing TLS-at-reverse-proxy precedent, not built into this pass.

## 7. Testing approach

Consistent with this codebase's established convention (pure Mockito unit tests, no `@SpringBootTest`/`MockMvc`):

- `AccessLogService`: `logLogin`/`logDownload` call `accessLogRepository.save` with correctly-populated fields (mocked `GeoLocationService` returning a fixed location); a repository-save exception is caught and does not propagate.
- `GeoLocationService`: cannot easily unit-test the real `.mmdb` lookup without shipping a test fixture database (MaxMind provides small test databases for exactly this purpose) — if a lightweight test `.mmdb` fixture is impractical to add, this class's test coverage is limited to the constructor's missing-file-handles-gracefully path (verifiable without a real database) and `resolveLocation` returning empty when the reader is null; the real lookup path is verified manually via boot + a real request, consistent with this session's established pattern for genuinely hard-to-unit-test integration points.
- `SubmissionService`: `src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java` already exists — extend it for `computeContentHash`'s duplicate-detection wiring: a matching hash sets `possibleDuplicate`/`duplicateOfPaperVersionId` correctly; no match leaves both at their defaults; a hashing failure doesn't block the save.
- `ReviewRestController`: no existing test file (confirmed) — create one, covering the new `AccessLogService.logDownload` call on successful download.
- `AdminAccessLogController`: new, create its test following this session's established Mockito+`ExtendedModelMap` pattern (e.g. `AdminRegistrationControllerTest`).
- Manual boot verification: the actual GeoLite2 resolution against a real request (localhost resolves to nothing, expected), the admin access-log page rendering, and the duplicate-detection flag appearing after a byte-identical re-upload.

## 8. Deferred / explicitly out of scope

- Failed-login-attempt logging.
- Filter/search UI on the admin access-log page.
- Retention enforcement (scheduled cleanup job) — documented policy only.
- Bank-slip view logging as a distinct event type.
- Roadmap item #12 (Proceedings + Zenodo) — dropped from the roadmap entirely, not merely deferred.
- `X-Forwarded-For` reverse-proxy IP resolution — deployment configuration, not application code.
