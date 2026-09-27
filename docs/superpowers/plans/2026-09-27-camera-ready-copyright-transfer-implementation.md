# Camera-Ready Stage + Copyright-Transfer Collection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `CAMERA_READY_SUBMITTED` paper status entered after acceptance, requiring the author to upload a final formatted PDF and acknowledge copyright transfer, and make `ProceedingsService` require this stage before including a paper.

**Architecture:** One new enum value, two new nullable fields on `PaperVersion`, one new `SubmissionService` method modeled tightly on the existing `uploadRevision`, one new REST endpoint, two `ProceedingsService` query/selection changes, one new chair-facing list view, and an extension of the existing author-facing "My Submissions" dashboard and admin "Paper Detail" page.

**Tech Stack:** Spring Boot 3.5.8, Java 21, JPA/Hibernate, Thymeleaf, JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-27-camera-ready-copyright-transfer-design.md`

**Global Constraints:**
- Do not touch `uploadNewVersion`, `uploadRevision`, or `enforceRevisionDeadline` — camera-ready is a fully separate code path with no deadline concept in this pass.
- New nullable fields on `PaperVersion` must not require a `data-dev.sql` seed-data edit. `cameraReady` is `boolean` with a `= false` default (same as the existing `possibleDuplicate` field) — never null, so it needs no seed-data change since every pre-existing `INSERT INTO paper_versions` row will get Hibernate's/H2's column default. `copyrightTransferAgreedAt` is a nullable `Instant` — also needs no seed-data change. Verify this in Task 1's own steps (boot the dev profile) rather than assuming.
- `IllegalArgumentException` is not currently mapped to any HTTP status by `SubmissionRestController`'s existing endpoints (confirmed: only `SecurityException`→403 and `IllegalStateException`→409 exist). Do not introduce a new exception→status mapping. `SubmissionService.uploadCameraReady` must throw `IllegalStateException` (not `IllegalArgumentException`) for the missing-copyright-agreement case, so it naturally maps to 409 through the controller's existing catch block — this deviates from the spec's Section 4.1 code sketch, which is a spec defect corrected here (ruling: the controller's existing, tested exception vocabulary is the binding pattern; the spec's sketch was written before this file was re-read in full).
- Follow the `actingUser()`-via-`SecurityContextHolder` pattern exactly as it appears in `SubmissionRestController`, `AdminDecisionViewController`, and `ReviewRestController` for any new controller code — do not introduce a different auth-lookup style.
- Run `./gradlew test` after every task; the suite must stay green (currently 205 tests, 0 failures) before moving to the next task.

---

### Task 1: Domain model — `PaperStatus` enum value + `PaperVersion` fields

**Files:**
- Modify: `src/main/java/org/confcms/cms/submission/domain/PaperStatus.java`
- Modify: `src/main/java/org/confcms/cms/submission/domain/PaperVersion.java`

- [ ] **Step 1: Add the new enum value**

Edit `src/main/java/org/confcms/cms/submission/domain/PaperStatus.java` to:

```java
package org.confcms.cms.submission.domain;

public enum PaperStatus {
    SUBMITTED,
    UNDER_REVIEW,
    ACCEPTED,
    REJECTED,
    DESK_REJECTED,
    WITHDRAWN,
    MINOR_REVISION,
    MAJOR_REVISION,
    CAMERA_READY_SUBMITTED
}
```

- [ ] **Step 2: Add the two new fields to `PaperVersion`**

Edit `src/main/java/org/confcms/cms/submission/domain/PaperVersion.java` — add these two fields at the end of the class, before the closing brace, following the exact style of the existing `possibleDuplicate` field (boolean, `nullable = false`, defaulted) and the existing `plagiarismScore`/`plagiarismNote` fields (nullable, one-line comment explaining when it's set):

```java
    // True only for the version uploaded specifically as the camera-ready submission
    // (via SubmissionService.uploadCameraReady), never for a regular version/revision upload.
    @Column(nullable = false)
    private boolean cameraReady = false;

    // Set the moment the author checks "I agree to transfer copyright" during camera-ready
    // upload. Null until then -- no separate entity/e-signature workflow in this pass.
    private java.time.Instant copyrightTransferAgreedAt;
```

The full file's field list after this change (for verification, do not paste this as a diff — this shows the resulting shape only):
`paper`, `versionNumber`, `filePath`, `originalFilename`, `contentHash`, `possibleDuplicate`, `duplicateOfPaperVersionId`, `plagiarismScore`, `plagiarismNote`, `cameraReady`, `copyrightTransferAgreedAt`.

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Boot the dev profile to confirm no seed-data breakage**

Run: `SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun > /tmp/camera-ready-task1-boot.log 2>&1 &` then poll the log (`grep -q "Started ConferenceCmsApplication" /tmp/camera-ready-task1-boot.log`) for up to 60 seconds.
Expected: `Started ConferenceCmsApplication` appears, no `ERROR`/`Exception`/`APPLICATION FAILED` lines. This confirms `cameraReady`'s `NOT NULL` boolean column doesn't break `data-dev.sql`'s existing `INSERT INTO paper_versions` rows (H2/Hibernate applies the Java-side default `= false` only for JPA-inserted rows, not raw SQL inserts missing the column — if this boot fails with a `NULL not allowed for column "CAMERA_READY"` or similar, add `camera_ready = FALSE` to every existing `INSERT INTO paper_versions` statement in `src/main/resources/data-dev.sql` and re-verify).
Then stop the app: find the PID via `lsof -i :8080 -sTCP:LISTEN -t` and `kill -9` it; confirm the port is free.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 205 tests passing (no new tests yet — this task only touches domain model, not behavior).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/confcms/cms/submission/domain/PaperStatus.java src/main/java/org/confcms/cms/submission/domain/PaperVersion.java
git commit -m "feat: add CAMERA_READY_SUBMITTED status and camera-ready fields to PaperVersion"
```

---

### Task 2: `SubmissionService.uploadCameraReady`

**Files:**
- Modify: `src/main/java/org/confcms/cms/submission/service/SubmissionService.java`
- Test: `src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java` (read this file first — if it does not exist at this path, locate the actual existing test file for `SubmissionService` via `find src/test -iname "SubmissionServiceTest.java"` and use that path/existing test style instead; do not create a second test file for the same class)

- [ ] **Step 1: Read the existing `uploadRevision` method and its tests first**

Read `src/main/java/org/confcms/cms/submission/service/SubmissionService.java` in full (already shown in this plan's context below) and the existing test file's tests for `uploadRevision` specifically, to match exact mocking/assertion style (constructor argument order, `@Mock` field order, `verify(...)` usage).

Current `uploadRevision` for reference (do not modify this method):
```java
@Transactional
public Paper uploadRevision(User requester, Long paperId, MultipartFile file) {
    Paper paper = paperRepository.findById(paperId)
            .orElseThrow(() -> new IllegalArgumentException("Paper not found"));

    boolean isOwner = paper.getSubmitter().getId().equals(requester.getId());
    boolean isAdmin = requester.getRole() == org.confcms.cms.core.security.Role.ADMIN;
    if (!isOwner && !isAdmin) {
        throw new SecurityException("Not authorized to upload a revision for this paper");
    }

    if (paper.getStatus() != PaperStatus.MINOR_REVISION && paper.getStatus() != PaperStatus.MAJOR_REVISION) {
        throw new IllegalStateException("This paper is not currently awaiting a revision");
    }

    enforceRevisionDeadline(paper);

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
}
```

- [ ] **Step 2: Write the failing tests**

`src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java` has NO shared `@BeforeEach`/field for the service under test — every existing test constructs it inline as `new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository)` (7 args, this exact order — confirm this order still matches the class's current field declaration order before using it, per `@RequiredArgsConstructor` semantics). Follow that inline-construction style exactly — do not introduce a shared field or `@BeforeEach` the rest of the file doesn't have. `@Mock` fields already present: `paperRepository`, `fileStorageService`, `emailService`, `conferenceService`, `personInvitationService`, `userRepository`, `paperVersionRepository` — reuse them, do not add duplicates.

Add these tests to the existing file:

```java
@Test
void uploadCameraReadySetsStatusAndFlagsVersionWhenPaperIsAccepted() {
    SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

    Paper paper = new Paper();
    paper.setId(1L);
    paper.setTitle("Accepted Paper");
    paper.setStatus(PaperStatus.ACCEPTED);
    User submitter = new User();
    submitter.setId(5L);
    submitter.setEmail("author@example.com");
    submitter.setFullName("Author Name");
    paper.setSubmitter(submitter);

    when(paperRepository.findById(1L)).thenReturn(Optional.of(paper));
    MultipartFile file = new MockMultipartFile("file", "camera-ready.pdf", "application/pdf",
            "%PDF-1.4 fake content".getBytes());
    when(fileStorageService.store(file)).thenReturn("/uploads/camera-ready.pdf");
    when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Paper result = service.uploadCameraReady(submitter, 1L, file, true);

    assertThat(result.getStatus()).isEqualTo(PaperStatus.CAMERA_READY_SUBMITTED);
    assertThat(result.getVersions()).hasSize(1);
    PaperVersion version = result.getVersions().get(0);
    assertThat(version.isCameraReady()).isTrue();
    assertThat(version.getCopyrightTransferAgreedAt()).isNotNull();
}

@Test
void uploadCameraReadyRejectsWhenPaperNotAccepted() {
    SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

    Paper paper = new Paper();
    paper.setId(2L);
    paper.setStatus(PaperStatus.UNDER_REVIEW);
    User submitter = new User();
    submitter.setId(5L);
    paper.setSubmitter(submitter);

    when(paperRepository.findById(2L)).thenReturn(Optional.of(paper));
    MultipartFile file = new MockMultipartFile("file", "camera-ready.pdf", "application/pdf",
            "%PDF-1.4 fake content".getBytes());

    assertThatThrownBy(() -> service.uploadCameraReady(submitter, 2L, file, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not currently awaiting a camera-ready submission");
}

@Test
void uploadCameraReadyRejectsWhenCopyrightNotAgreed() {
    SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

    Paper paper = new Paper();
    paper.setId(3L);
    paper.setStatus(PaperStatus.ACCEPTED);
    User submitter = new User();
    submitter.setId(5L);
    paper.setSubmitter(submitter);

    when(paperRepository.findById(3L)).thenReturn(Optional.of(paper));
    MultipartFile file = new MockMultipartFile("file", "camera-ready.pdf", "application/pdf",
            "%PDF-1.4 fake content".getBytes());

    assertThatThrownBy(() -> service.uploadCameraReady(submitter, 3L, file, false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Copyright transfer must be agreed to");
}

@Test
void uploadCameraReadyRejectsNonOwnerNonAdmin() {
    SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository, paperVersionRepository);

    Paper paper = new Paper();
    paper.setId(4L);
    paper.setStatus(PaperStatus.ACCEPTED);
    User submitter = new User();
    submitter.setId(5L);
    paper.setSubmitter(submitter);

    User stranger = new User();
    stranger.setId(99L);
    stranger.setRole(org.confcms.cms.core.security.Role.AUTHOR);

    when(paperRepository.findById(4L)).thenReturn(Optional.of(paper));
    MultipartFile file = new MockMultipartFile("file", "camera-ready.pdf", "application/pdf",
            "%PDF-1.4 fake content".getBytes());

    assertThatThrownBy(() -> service.uploadCameraReady(stranger, 4L, file, true))
            .isInstanceOf(SecurityException.class);
}
```

Add `import static org.mockito.ArgumentMatchers.any;` and `import org.springframework.mock.web.MockMultipartFile;` and `import org.confcms.cms.submission.domain.PaperVersion;` to the test file's imports if not already present. Confirm `org.confcms.cms.core.security.Role.AUTHOR` is the correct enum constant name by reading `src/main/java/org/confcms/cms/core/security/Role.java` first — substitute the correct non-admin role name if `AUTHOR` is not exactly it.

- [ ] **Step 3: Run the new tests to verify they fail**

Run: `./gradlew test --tests "*SubmissionServiceTest*"`
Expected: FAIL — compile error, `uploadCameraReady` method does not exist yet.

- [ ] **Step 4: Implement `uploadCameraReady`**

Add this method to `src/main/java/org/confcms/cms/submission/service/SubmissionService.java`, placed immediately after `uploadRevision`:

```java
@Transactional
public Paper uploadCameraReady(User requester, Long paperId, MultipartFile file, boolean copyrightAgreed) {
    Paper paper = paperRepository.findById(paperId)
            .orElseThrow(() -> new IllegalArgumentException("Paper not found"));

    boolean isOwner = paper.getSubmitter().getId().equals(requester.getId());
    boolean isAdmin = requester.getRole() == org.confcms.cms.core.security.Role.ADMIN;
    if (!isOwner && !isAdmin) {
        throw new SecurityException("Not authorized to upload a camera-ready version for this paper");
    }

    if (paper.getStatus() != PaperStatus.ACCEPTED) {
        throw new IllegalStateException("This paper is not currently awaiting a camera-ready submission");
    }

    if (!copyrightAgreed) {
        throw new IllegalStateException("Copyright transfer must be agreed to before submitting the camera-ready version");
    }

    validatePdf(file);
    String contentHash = computeContentHash(file);
    String filePath = fileStorageService.store(file);

    int newVersionNumber = paper.getVersions().size() + 1;
    PaperVersion version = new PaperVersion();
    version.setPaper(paper);
    version.setVersionNumber(newVersionNumber);
    version.setFilePath(filePath);
    version.setOriginalFilename(file.getOriginalFilename());
    version.setCameraReady(true);
    version.setCopyrightTransferAgreedAt(java.time.Instant.now());
    applyDuplicateCheck(version, contentHash);
    paper.getVersions().add(version);

    paper.setStatus(PaperStatus.CAMERA_READY_SUBMITTED);
    Paper saved = paperRepository.save(paper);

    try {
        emailService.sendSimpleEmail(paper.getSubmitter().getEmail(), "Camera-ready version received",
                "Your camera-ready version (v" + newVersionNumber + ") was received for your paper: " + paper.getTitle());
    } catch (Exception ignored) {
    }

    return saved;
}
```

Note: this throws `IllegalStateException` for the missing-copyright-agreement case, per this plan's Global Constraints (not `IllegalArgumentException` as the spec's draft code sketch showed) — the test in Step 2 already asserts this.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "*SubmissionServiceTest*"`
Expected: PASS, all 4 new tests plus all existing tests in that file green.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 209 tests passing.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/submission/service/SubmissionService.java src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java
git commit -m "feat: add SubmissionService.uploadCameraReady"
```

(Adjust the test file path in the `git add` if Step 1 found it at a different location.)

---

### Task 3: REST endpoint — `POST /submission/{id}/camera-ready`

**Files:**
- Modify: `src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java`
- Test: locate the existing test file for this controller via `find src/test -iname "SubmissionRestControllerTest.java"` (if none exists, note that in your report and skip to Step 5 — do not create a new test file/framework for a controller that has no existing test coverage; this task's manual-verification step in Task 7 covers it instead)

- [ ] **Step 1: Read the controller in full**

Full current content of `src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java` is:

```java
package org.confcms.cms.web.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.domain.User;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperAuthor;
import org.confcms.cms.submission.dto.AuthorRequestDto;
import org.confcms.cms.submission.service.SubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/submission")
@RequiredArgsConstructor
public class SubmissionRestController {

    private final SubmissionService submissionService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ... submitPaper, mySubmissions, uploadVersion, uploadRevision, withdraw ...
}
```

(Full method bodies already shown in this plan's preceding context if you need them — `uploadRevision` is the template to copy.)

- [ ] **Step 2: Add the new endpoint**

Add this method immediately after `uploadRevision` in `SubmissionRestController`:

```java
@PostMapping(path = "/{id}/camera-ready", consumes = "multipart/form-data")
public ResponseEntity<?> uploadCameraReady(@PathVariable Long id,
                                            @RequestParam("file") MultipartFile file,
                                            @RequestParam boolean copyrightAgreed) {
    String email = SecurityContextHolder.getContext().getAuthentication().getName();
    User user = userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    try {
        Paper saved = submissionService.uploadCameraReady(user, id, file, copyrightAgreed);
        return ResponseEntity.ok(saved);
    } catch (SecurityException se) {
        return ResponseEntity.status(403).body(se.getMessage());
    } catch (IllegalStateException ise) {
        return ResponseEntity.status(409).body(ise.getMessage());
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: If an existing controller test file was found in the pre-step search, add tests matching its style**

If `SubmissionRestControllerTest.java` (or similarly named) exists, add tests for: happy path (200, body contains updated paper), non-owner (403), wrong-status (409), copyright-not-agreed (409) — mirroring however that file already tests `uploadRevision`. If no such file exists, skip this step (do not invent a new test harness for an untested controller in this task — Task 7's manual verification covers this endpoint).

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests green (209, or more if Step 4 added tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java
git commit -m "feat: add POST /submission/{id}/camera-ready endpoint"
```

(Include the test file in this commit's `git add` too if Step 4 modified one.)

---

### Task 4: `ProceedingsService` — require camera-ready, select the flagged version

**Files:**
- Modify: `src/main/java/org/confcms/cms/service/ProceedingsService.java`
- Test: `find src/test -iname "ProceedingsServiceTest.java"` — read it first if it exists; if it doesn't exist, create it at `src/test/java/org/confcms/cms/service/ProceedingsServiceTest.java`

- [ ] **Step 1: Read the current file in full**

Already shown in this plan's context above (`ProceedingsService.java`, 105 lines). The two call sites to change are `generateProceedings` (line ~30: `paperRepository.findByStatus(PaperStatus.ACCEPTED)`) and `exportBibTeX` (line ~91: same call), plus `generateProceedings`'s version-selection loop (lines ~45-53).

- [ ] **Step 2: Write the failing test (create the file if it doesn't exist)**

If creating fresh, use this full file (adjust imports/mocking style to match sibling test files like `SubmissionServiceTest` if an existing `ProceedingsServiceTest` reveals a different established style — read that file first if found):

```java
package org.confcms.cms.service;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.domain.PaperVersion;
import org.confcms.cms.submission.repository.PaperRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProceedingsServiceTest {

    @Mock
    private PaperRepository paperRepository;

    @Test
    void generateProceedingsOnlyQueriesCameraReadySubmittedPapers() throws Exception {
        ProceedingsService service = new ProceedingsService(paperRepository);
        when(paperRepository.findByStatus(PaperStatus.CAMERA_READY_SUBMITTED)).thenReturn(List.of());

        Conference conference = new Conference();
        conference.setTitle("Test Conf");
        conference.setStartDate(LocalDate.of(2026, 1, 1));

        File outputFile = File.createTempFile("proceedings-test", ".pdf");
        outputFile.deleteOnExit();

        service.generateProceedings(conference, outputFile.getAbsolutePath());

        verify(paperRepository).findByStatus(PaperStatus.CAMERA_READY_SUBMITTED);
    }

    @Test
    void exportBibTeXOnlyQueriesCameraReadySubmittedPapers() throws Exception {
        ProceedingsService service = new ProceedingsService(paperRepository);
        when(paperRepository.findByStatus(PaperStatus.CAMERA_READY_SUBMITTED)).thenReturn(List.of());

        Conference conference = new Conference();
        conference.setTitle("Test Conf");
        conference.setStartDate(LocalDate.of(2026, 1, 1));

        File outputFile = File.createTempFile("bibtex-test", ".bib");
        outputFile.deleteOnExit();

        service.exportBibTeX(conference, outputFile.getAbsolutePath());

        verify(paperRepository).findByStatus(PaperStatus.CAMERA_READY_SUBMITTED);
    }

    @Test
    void generateProceedingsSelectsTheCameraReadyFlaggedVersionNotJustTheLastOne() throws Exception {
        ProceedingsService service = new ProceedingsService(paperRepository);

        Paper paper = new Paper();
        paper.setId(1L);
        paper.setTitle("A Paper");
        paper.setStatus(PaperStatus.CAMERA_READY_SUBMITTED);

        File cameraReadyPdf = File.createTempFile("camera-ready", ".pdf");
        writeMinimalPdf(cameraReadyPdf);
        cameraReadyPdf.deleteOnExit();

        PaperVersion cameraReadyVersion = new PaperVersion();
        cameraReadyVersion.setVersionNumber(2);
        cameraReadyVersion.setFilePath(cameraReadyPdf.getAbsolutePath());
        cameraReadyVersion.setCameraReady(true);
        paper.getVersions().add(cameraReadyVersion);

        // A later, non-camera-ready version should NOT be selected even though it's later in the list.
        File laterNonCameraReadyPdf = File.createTempFile("later-non-camera-ready", ".pdf");
        writeMinimalPdf(laterNonCameraReadyPdf);
        laterNonCameraReadyPdf.deleteOnExit();
        PaperVersion laterVersion = new PaperVersion();
        laterVersion.setVersionNumber(3);
        laterVersion.setFilePath(laterNonCameraReadyPdf.getAbsolutePath());
        laterVersion.setCameraReady(false);
        paper.getVersions().add(laterVersion);

        when(paperRepository.findByStatus(PaperStatus.CAMERA_READY_SUBMITTED)).thenReturn(List.of(paper));

        Conference conference = new Conference();
        conference.setTitle("Test Conf");
        conference.setStartDate(LocalDate.of(2026, 1, 1));

        File outputFile = File.createTempFile("proceedings-selection-test", ".pdf");
        outputFile.deleteOnExit();

        // Should not throw -- PDFMergerUtility will only be asked to merge the cover, TOC, and the
        // camera-ready-flagged PDF. This test's main assertion is implicit: it must not attempt to
        // merge laterNonCameraReadyPdf. A stronger assertion would inspect merger internals, which
        // PDFMergerUtility does not expose; the absence of an exception plus the explicit filter in
        // the implementation (verified by code review in this task's review step) is the coverage
        // available without a heavier PDF-parsing assertion.
        service.generateProceedings(conference, outputFile.getAbsolutePath());
    }

    private void writeMinimalPdf(File file) throws Exception {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(file);
        }
    }
}
```

- [ ] **Step 3: Run the new tests to verify they fail**

Run: `./gradlew test --tests "*ProceedingsServiceTest*"`
Expected: FAIL — `findByStatus(PaperStatus.CAMERA_READY_SUBMITTED)` is never called (current code calls `findByStatus(PaperStatus.ACCEPTED)`), and `isCameraReady()`/`setCameraReady()` may not exist yet if Task 1 wasn't completed first (it was — this task runs after Task 1).

- [ ] **Step 4: Implement the changes**

In `src/main/java/org/confcms/cms/service/ProceedingsService.java`:

Change (in `generateProceedings`):
```java
List<Paper> acceptedPapers = paperRepository.findByStatus(PaperStatus.ACCEPTED);
```
to:
```java
List<Paper> acceptedPapers = paperRepository.findByStatus(PaperStatus.CAMERA_READY_SUBMITTED);
```

Change the paper-PDF-selection loop from:
```java
// 3. Add Papers
for (Paper paper : acceptedPapers) {
    if (!paper.getVersions().isEmpty()) {
        var latest = paper.getVersions().get(paper.getVersions().size() - 1);
        File pdfFile = new File(latest.getFilePath());
        if (pdfFile.exists()) {
            merger.addSource(pdfFile);
        }
    }
}
```
to:
```java
// 3. Add Papers (specifically the camera-ready-flagged version, not "last uploaded" --
// a paper can have later non-camera-ready versions e.g. from before it reached this stage
// in a different run, so "last in the list" is not a safe substitute for the explicit flag)
for (Paper paper : acceptedPapers) {
    paper.getVersions().stream()
            .filter(PaperVersion::isCameraReady)
            .findFirst()
            .ifPresent(cameraReadyVersion -> {
                File pdfFile = new File(cameraReadyVersion.getFilePath());
                if (pdfFile.exists()) {
                    merger.addSource(pdfFile);
                }
            });
}
```

Add the import `import org.confcms.cms.submission.domain.PaperVersion;` to this file.

Change (in `exportBibTeX`):
```java
List<Paper> acceptedPapers = paperRepository.findByStatus(PaperStatus.ACCEPTED);
```
to:
```java
List<Paper> acceptedPapers = paperRepository.findByStatus(PaperStatus.CAMERA_READY_SUBMITTED);
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "*ProceedingsServiceTest*"`
Expected: PASS, all 3 tests green.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 212 tests passing.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/service/ProceedingsService.java src/test/java/org/confcms/cms/service/ProceedingsServiceTest.java
git commit -m "feat: require camera-ready status and flagged version for proceedings generation"
```

---

### Task 5: Chair-facing camera-ready status list

**Files:**
- Modify: `src/main/java/org/confcms/cms/web/controller/AdminDecisionViewController.java`
- Create: `src/main/resources/templates/admin/camera_ready.html`
- Modify: `src/main/resources/templates/admin/decisions.html`
- Test: `src/test/java/org/confcms/cms/web/controller/AdminDecisionViewControllerTest.java`

- [ ] **Step 1: Read the current controller in full**

Already shown in full in this plan's preceding context (`AdminDecisionViewController.java`) — fields: `decisionService`, `paperRepository`, `reviewRepository`, `committeeService`, `userRepository`; methods: `actingUser()`, `ui()`, `paperDetail()`, `recordPlagiarismCheck()`.

- [ ] **Step 2: Write the failing tests**

Add to `src/test/java/org/confcms/cms/web/controller/AdminDecisionViewControllerTest.java` (matching that file's existing `@Mock`/`authenticateAs` helper style, already shown in this plan's context):

```java
@Test
void cameraReadyStatusShowsAcceptedAndCameraReadySubmittedPapersForAdmin() {
    User admin = new User();
    admin.setId(40L);
    admin.setEmail("admin@example.com");
    admin.setRole(Role.ADMIN);
    authenticateAs(admin);

    Paper acceptedPaper = new Paper();
    acceptedPaper.setId(6L);
    acceptedPaper.setStatus(org.confcms.cms.submission.domain.PaperStatus.ACCEPTED);
    acceptedPaper.setConference(conference);

    Paper cameraReadyPaper = new Paper();
    cameraReadyPaper.setId(7L);
    cameraReadyPaper.setStatus(org.confcms.cms.submission.domain.PaperStatus.CAMERA_READY_SUBMITTED);
    cameraReadyPaper.setConference(conference);

    when(paperRepository.findByStatus(org.confcms.cms.submission.domain.PaperStatus.ACCEPTED))
            .thenReturn(java.util.List.of(acceptedPaper));
    when(paperRepository.findByStatus(org.confcms.cms.submission.domain.PaperStatus.CAMERA_READY_SUBMITTED))
            .thenReturn(java.util.List.of(cameraReadyPaper));

    Model model = new ExtendedModelMap();
    String view = controller.cameraReadyStatus(model);

    assertThat(view).isEqualTo("admin/camera_ready");
    @SuppressWarnings("unchecked")
    java.util.List<Paper> papers = (java.util.List<Paper>) model.getAttribute("papers");
    assertThat(papers).containsExactlyInAnyOrder(acceptedPaper, cameraReadyPaper);
}

@Test
void cameraReadyStatusFiltersToOwnConferenceForNonAdminChair() {
    User chair = new User();
    chair.setId(41L);
    chair.setEmail("chair@example.com");
    chair.setRole(Role.REVIEWER);
    authenticateAs(chair);

    Conference otherConference = new Conference();
    otherConference.setId(99L);

    Paper ownPaper = new Paper();
    ownPaper.setId(6L);
    ownPaper.setStatus(org.confcms.cms.submission.domain.PaperStatus.ACCEPTED);
    ownPaper.setConference(conference);

    Paper otherPaper = new Paper();
    otherPaper.setId(8L);
    otherPaper.setStatus(org.confcms.cms.submission.domain.PaperStatus.ACCEPTED);
    otherPaper.setConference(otherConference);

    when(paperRepository.findByStatus(org.confcms.cms.submission.domain.PaperStatus.ACCEPTED))
            .thenReturn(java.util.List.of(ownPaper, otherPaper));
    when(paperRepository.findByStatus(org.confcms.cms.submission.domain.PaperStatus.CAMERA_READY_SUBMITTED))
            .thenReturn(java.util.List.of());
    when(committeeService.isChairOrCoChair(chair, conference)).thenReturn(true);
    when(committeeService.isChairOrCoChair(chair, otherConference)).thenReturn(false);

    Model model = new ExtendedModelMap();
    controller.cameraReadyStatus(model);

    @SuppressWarnings("unchecked")
    java.util.List<Paper> papers = (java.util.List<Paper>) model.getAttribute("papers");
    assertThat(papers).containsExactly(ownPaper);
}
```

Add `import org.confcms.cms.submission.domain.Paper;` to this test file's imports if not already present (it is, from the plagiarism-check tests added previously).

- [ ] **Step 3: Run the new tests to verify they fail**

Run: `./gradlew test --tests "*AdminDecisionViewControllerTest*"`
Expected: FAIL — compile error, `cameraReadyStatus` method does not exist and `paperRepository` field may need adding as a `@Mock` if not already present (it is already a constructor argument to `AdminDecisionViewController`, confirm the test's `@Mock` list already includes it from the existing `paperDetail` tests — it does).

- [ ] **Step 4: Implement the controller method**

Add to `src/main/java/org/confcms/cms/web/controller/AdminDecisionViewController.java`, immediately after `paperDetail`:

```java
@GetMapping("/ui/camera-ready")
public String cameraReadyStatus(Model model) {
    User actingUser = actingUser();
    java.util.List<org.confcms.cms.submission.domain.Paper> papers = new java.util.ArrayList<>();
    papers.addAll(paperRepository.findByStatus(org.confcms.cms.submission.domain.PaperStatus.ACCEPTED));
    papers.addAll(paperRepository.findByStatus(org.confcms.cms.submission.domain.PaperStatus.CAMERA_READY_SUBMITTED));

    boolean isAdmin = actingUser.getRole() == Role.ADMIN;
    java.util.List<org.confcms.cms.submission.domain.Paper> visible = isAdmin ? papers : papers.stream()
            .filter(p -> committeeService.isChairOrCoChair(actingUser, p.getConference()))
            .toList();

    model.addAttribute("papers", visible);
    return "admin/camera_ready";
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "*AdminDecisionViewControllerTest*"`
Expected: PASS, all tests in that file green (existing + 2 new).

- [ ] **Step 6: Create the template**

Create `src/main/resources/templates/admin/camera_ready.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8" />
    <title>Camera-Ready Status</title>
    <style>
        table { border-collapse: collapse; width: 100%; }
        th, td { padding: 8px; border: 1px solid #ddd; }
        th { background: #f4f4f4; }
    </style>
</head>
<body>
<h1>Camera-Ready Status</h1>
<a th:href="@{/admin/decisions/ui}">Back to Decision Suggestions</a>
<table>
    <thead>
        <tr><th>Paper ID</th><th>Title</th><th>Conference</th><th>Status</th></tr>
    </thead>
    <tbody>
    <tr th:each="p : ${papers}">
        <td th:text="${p.id}"></td>
        <td><a th:href="@{'/admin/decisions/ui/paper/' + ${p.id}}" th:text="${p.title}"></a></td>
        <td th:text="${p.conference.title}"></td>
        <td th:text="${p.status}"></td>
    </tr>
    </tbody>
</table>
</body>
</html>
```

- [ ] **Step 7: Link from `admin/decisions.html`**

Add this line to `src/main/resources/templates/admin/decisions.html`, immediately after the `<h1>Decision Suggestions</h1>` line:

```html
<p><a th:href="@{/admin/decisions/ui/camera-ready}">View Camera-Ready Status</a></p>
```

- [ ] **Step 8: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 214 tests passing.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/AdminDecisionViewController.java src/main/resources/templates/admin/camera_ready.html src/main/resources/templates/admin/decisions.html src/test/java/org/confcms/cms/web/controller/AdminDecisionViewControllerTest.java
git commit -m "feat: add chair-facing camera-ready status list"
```

---

### Task 6: Extend `admin/paper_detail.html` and `author/submissions.html`

**Files:**
- Modify: `src/main/resources/templates/admin/paper_detail.html`
- Modify: `src/main/resources/templates/author/submissions.html`
- Modify: `src/main/java/org/confcms/cms/web/controller/AuthorDashboardController.java` (no method signature change — verify the model already exposes what the template needs; add nothing unless the template step below shows a gap)

- [ ] **Step 1: Add a Camera-Ready column to the admin Versions table**

In `src/main/resources/templates/admin/paper_detail.html`, change the Versions table's header from:
```html
<tr><th>Version</th><th>Filename</th><th>File Path</th><th>Plagiarism/Similarity</th></tr>
```
to:
```html
<tr><th>Version</th><th>Filename</th><th>File Path</th><th>Plagiarism/Similarity</th><th>Camera-Ready</th></tr>
```

And add a new `<td>` at the end of the `th:each="v : ${paper.versions}"` row, immediately after the existing Plagiarism/Similarity `<td>` block, before the closing `</tr>`:
```html
<td>
    <span th:if="${v.cameraReady}">
        Yes (<span th:text="${#temporals.format(v.copyrightTransferAgreedAt, 'yyyy-MM-dd')}"></span>)
    </span>
    <span th:unless="${v.cameraReady}">-</span>
</td>
```

(`copyrightTransferAgreedAt` is a `java.time.Instant` — confirm Thymeleaf's `#temporals` utility formats `Instant` directly in this project's Thymeleaf version; if it throws a `TemplateProcessingException` at manual-verification time in Task 7, fall back to `th:text="${v.copyrightTransferAgreedAt}"` with no formatting, which always works for any `Comparable`/`toString()`-able type.)

- [ ] **Step 2: Add a camera-ready upload form to the author dashboard**

Read the current `src/main/resources/templates/author/submissions.html` (created in roadmap item #10, shown in this plan's context) — its table has columns Title, Conference, Status, Latest Version, Registration/Payment. Add a sixth column:

Change the header row from:
```html
<tr>
    <th>Title</th>
    <th>Conference</th>
    <th>Status</th>
    <th>Latest Version</th>
    <th>Registration / Payment</th>
</tr>
```
to:
```html
<tr>
    <th>Title</th>
    <th>Conference</th>
    <th>Status</th>
    <th>Latest Version</th>
    <th>Registration / Payment</th>
    <th>Camera-Ready</th>
</tr>
```

Add a new `<td>` immediately before the closing `</tr>` of the `th:each="paper : ${papers}"` row:
```html
<td>
    <form th:if="${paper.status.name() == 'ACCEPTED'}"
          th:action="@{'/submission/' + ${paper.id} + '/camera-ready'}"
          method="post" enctype="multipart/form-data">
        <input type="file" name="file" accept="application/pdf" required="required" /><br/>
        <label>
            <input type="checkbox" name="copyrightAgreed" value="true" required="required" />
            I agree to transfer copyright
        </label>
        <button type="submit">Submit Camera-Ready</button>
    </form>
    <span th:if="${paper.status.name() == 'CAMERA_READY_SUBMITTED'}">Submitted</span>
    <span th:if="${paper.status.name() != 'ACCEPTED' and paper.status.name() != 'CAMERA_READY_SUBMITTED'}">-</span>
</td>
```

- [ ] **Step 3: Compile and run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 214 tests passing (no new Java tests in this task — template-only change; Task 7's manual verification is this task's real test).

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/templates/admin/paper_detail.html src/main/resources/templates/author/submissions.html
git commit -m "feat: surface camera-ready status/upload on paper detail and author dashboard"
```

---

### Task 7: Final manual end-to-end verification

**Files:** none (verification only)

- [ ] **Step 1: Boot the dev profile**

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun > /tmp/camera-ready-final-boot.log 2>&1 &
```
Poll for `Started ConferenceCmsApplication` in the log (up to 60s), confirm no `ERROR`/`Exception`.

- [ ] **Step 2: Log in as the seeded author and reach the dashboard**

Using real cookie-based login (the seed passwords were fixed in a prior commit `e440e25` — `author@example.com` / `author` now actually works):
```bash
curl -s -c /tmp/cr-cookies.txt -o /tmp/cr-login.html http://localhost:8080/login
CSRF=$(grep -o 'name="_csrf" value="[^"]*"' /tmp/cr-login.html | sed -E 's/.*value="([^"]*)"/\1/')
curl -s -b /tmp/cr-cookies.txt -c /tmp/cr-cookies.txt -D - -o /dev/null -X POST http://localhost:8080/login \
  --data-urlencode "username=author@example.com" --data-urlencode "password=author" \
  --data-urlencode "_csrf=$CSRF" | grep -i "^location"
```
Expected: `Location: http://localhost:8080/dashboard` (not `/login?error`).

- [ ] **Step 3: Log in as admin, accept the seeded paper, confirm it needs camera-ready**

Log in as `admin@example.com` / `admin` the same way. The seeded paper (`Sample Paper on ML`, id likely `1`, currently seeded as `ACCEPTED` per `data-dev.sql`) is already `ACCEPTED` — visit `GET /admin/decisions/ui/camera-ready` as admin and confirm it appears in the list with status `ACCEPTED`.

If no seeded paper is currently `ACCEPTED` (re-check `data-dev.sql` at verification time — it may have changed since this plan was written), use the admin decision-apply endpoint to accept one: `POST /admin/decisions/{paperId}/apply?decision=ACCEPT` with an admin session, then re-check the camera-ready list.

- [ ] **Step 4: As the author, upload a camera-ready version**

```bash
echo '%PDF-1.4 fake camera ready content' > /tmp/camera-ready-test.pdf
curl -s -b /tmp/cr-cookies-author.txt -o /tmp/cr-upload-response.json -w "camera-ready upload -> %{http_code}\n" \
  -X POST http://localhost:8080/submission/1/camera-ready \
  -F "file=@/tmp/camera-ready-test.pdf;type=application/pdf" \
  -F "copyrightAgreed=true"
```
(Use the author's own cookie jar from Step 2, not admin's — adjust the paper id to whatever the accepted paper's real id is per Step 3.)
Expected: `200`, response body's `status` field is `CAMERA_READY_SUBMITTED`.

- [ ] **Step 5: Confirm the author dashboard reflects "Submitted"**

`curl -s -b <author-cookies> http://localhost:8080/author/submissions` and grep for `Submitted` in the output.

- [ ] **Step 6: Confirm the admin paper-detail page shows the camera-ready version**

`curl -s -b <admin-cookies> http://localhost:8080/admin/decisions/ui/paper/1` (or the real paper id) and grep for `Yes (` in the output.

- [ ] **Step 7: Stop the app**

```bash
PID=$(lsof -i :8080 -sTCP:LISTEN -t)
kill -9 $PID
lsof -i :8080 -sTCP:LISTEN -t || echo "PORT FREE"
```

- [ ] **Step 8: Clean up scratch files**

```bash
rm -f /tmp/cr-cookies.txt /tmp/cr-cookies-author.txt /tmp/cr-login.html /tmp/cr-upload-response.json /tmp/camera-ready-test.pdf /tmp/camera-ready-task1-boot.log /tmp/camera-ready-final-boot.log
```

No commit for this task — it is verification only. If any step fails, fix the underlying issue in the relevant earlier task's files and re-run this task's steps from the top.
