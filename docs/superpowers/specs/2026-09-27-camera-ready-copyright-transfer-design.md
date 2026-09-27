# Camera-Ready Stage + Copyright-Transfer Collection — Design Spec

**Roadmap item:** #11 (spec section 3.3 of `2026-09-18-evaluation-and-gap-analysis.md`)

**Status:** Approved for implementation planning.

## 1. Problem

Today, `PaperStatus` has no distinct stage between "accepted by the chair" and "included in the generated proceedings." `ProceedingsService.generateProceedings`/`exportBibTeX` both call `paperRepository.findByStatus(PaperStatus.ACCEPTED)` and, for each paper, blindly take `paper.getVersions().get(size() - 1)` — the most recently uploaded version, whatever it happens to be. There is no confirmation that this version is the author's final, formatted submission, and no record that the author has agreed to transfer copyright/publication rights, which every real conference-management tool (IEEE PDF eXpress, EasyChair, CMT) treats as a mandatory, separate step after acceptance.

## 2. Scope

**In scope:**
- A new `PaperStatus.CAMERA_READY_SUBMITTED` stage, entered when the author uploads a camera-ready version and acknowledges copyright transfer for an `ACCEPTED` paper.
- Copyright-transfer acknowledgment recorded as a checkbox + timestamp (no e-signature, no external document).
- `ProceedingsService` updated to require `CAMERA_READY_SUBMITTED` and to pull specifically the camera-ready-flagged version, not "last uploaded."
- A chair/admin-facing list of `ACCEPTED`/`CAMERA_READY_SUBMITTED` papers showing per-paper completion status.
- An author-facing upload action surfaced on the existing "My Submissions" dashboard (`author/submissions.html`, added in roadmap item #10).

**Out of scope (explicitly deferred, not part of this spec):**
- PDF/A or font-embedding validation ("is this PDF Xplore-compatible") — a large feature in its own right per the originating spec section, deliberately deferred there too.
- Institution-configurable formatting requirements (template name, page limits) shown to authors — a nice-to-have per the originating spec, not required for a first version.
- Full e-signature workflows for copyright transfer — checkbox + timestamp is explicitly sufficient per the originating spec.
- Any change to the `MINOR_REVISION`/`MAJOR_REVISION` review-revision workflow (`uploadRevision`) — camera-ready is a distinct, later stage and does not touch that code path.

## 3. Domain model changes

### 3.1 `PaperStatus` (new enum value)

```java
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

No migration required: Hibernate maps this enum to a `VARCHAR`/native-enum column via `@Enumerated(EnumType.STRING)`; adding a new constant does not require a schema change. (Confirm at implementation time whether the dev H2 profile uses a native `ENUM(...)` column type anywhere for `papers.status` — if so, that DDL must list the new value too. Checked: `papers.status` is `@Enumerated(EnumType.STRING)` with no explicit `columnDefinition`, so Hibernate generates a plain `VARCHAR`; no DDL enum list to update.)

### 3.2 `PaperVersion` (two new nullable fields)

```java
// True only for the version uploaded specifically as the camera-ready submission
// (via uploadCameraReady), never for a regular version/revision upload.
private boolean cameraReady = false;

// Set the moment the author checks "I agree to transfer copyright" during
// camera-ready upload. Null until then. No separate entity: per-instance
// acknowledgment (checkbox + timestamp) is explicitly sufficient for a first
// version; a full e-signature workflow is out of scope (see Section 2).
private Instant copyrightTransferAgreedAt;
```

Both fields are nullable/defaulted, following the pattern already established this session for `PaperVersion.plagiarismScore`/`plagiarismNote` and `possibleDuplicate` — a `NOT NULL` column added to this entity has twice broken `data-dev.sql`'s pre-existing seed INSERTs earlier in this project's history; nullable-by-default avoids a third occurrence.

## 4. Service layer

### 4.1 `SubmissionService.uploadCameraReady` (new method)

Modeled closely on the existing `uploadRevision` (same file, `src/main/java/org/confcms/cms/submission/service/SubmissionService.java`):

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
        throw new IllegalArgumentException("Copyright transfer must be agreed to before submitting the camera-ready version");
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
    version.setCopyrightTransferAgreedAt(Instant.now());
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

Notes:
- No revision-deadline check (`enforceRevisionDeadline`) — that mechanism belongs to the `MINOR_REVISION`/`MAJOR_REVISION` flow, not camera-ready. Camera-ready has no deadline concept in this pass.
- `copyrightAgreed == false` throws `IllegalArgumentException`, matching the exception type `SubmissionRestController`'s existing endpoints already map to 400 responses (verify at implementation time against the controller's existing exception-handling; if no handler currently maps `IllegalArgumentException` to 400 for this controller, add one consistent with the controller's existing style rather than leaving it to fall through to a 500).

### 4.2 `ProceedingsService` changes

Both `generateProceedings` and `exportBibTeX` change their query from:
```java
List<Paper> acceptedPapers = paperRepository.findByStatus(PaperStatus.ACCEPTED);
```
to:
```java
List<Paper> acceptedPapers = paperRepository.findByStatus(PaperStatus.CAMERA_READY_SUBMITTED);
```

`generateProceedings`'s paper-PDF-selection loop changes from "last version in the list" to "the version flagged camera-ready":

```java
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

(Exactly one version should ever be flagged `cameraReady` per paper, since `uploadCameraReady` is only reachable from `ACCEPTED` status and transitions the paper out of it immediately — `findFirst()` is a defensive convenience, not a hint that multiple camera-ready versions are expected.)

## 5. Controller layer

### 5.1 `SubmissionRestController` (new endpoint)

```java
@PostMapping(path = "/{id}/camera-ready", consumes = "multipart/form-data")
public ResponseEntity<?> uploadCameraReady(@PathVariable Long id,
                                            @RequestParam MultipartFile file,
                                            @RequestParam boolean copyrightAgreed) {
    try {
        Paper paper = submissionService.uploadCameraReady(actingUser(), id, file, copyrightAgreed);
        return ResponseEntity.ok(paper);
    } catch (SecurityException se) {
        return ResponseEntity.status(403).body(se.getMessage());
    } catch (IllegalStateException | IllegalArgumentException e) {
        return ResponseEntity.status(400).body(e.getMessage());
    }
}
```

Match this against the controller's existing endpoints (`/{id}/version`, `/{id}/revision`, `/{id}/withdraw`) at implementation time for exact exception-mapping consistency — the shape above follows the same pattern already used by `AdminDecisionController`'s endpoints (`SecurityException` → 403).

### 5.2 Chair-facing camera-ready list (new endpoint + template)

New method on `AdminDecisionViewController` (same file, same `actingUser()`/authorization pattern already used by `paperDetail`):

```java
@GetMapping("/ui/camera-ready")
public String cameraReadyStatus(Model model) {
    User actingUser = actingUser();
    List<Paper> papers = new ArrayList<>();
    papers.addAll(paperRepository.findByStatus(PaperStatus.ACCEPTED));
    papers.addAll(paperRepository.findByStatus(PaperStatus.CAMERA_READY_SUBMITTED));

    // Admins see all; chairs/co-chairs see only their own conference's papers.
    boolean isAdmin = actingUser.getRole() == Role.ADMIN;
    List<Paper> visible = isAdmin ? papers : papers.stream()
            .filter(p -> committeeService.isChairOrCoChair(actingUser, p.getConference()))
            .toList();

    model.addAttribute("papers", visible);
    return "admin/camera_ready";
}
```

New template `src/main/resources/templates/admin/camera_ready.html`, modeled on the existing table style in `admin/decisions.html`: one row per paper (title, conference, status, camera-ready submitted yes/no with timestamp if yes), linked from `admin/decisions.html`'s existing page via a simple nav link (same pattern as the `author/submissions.html` → `dashboard.html` link added in roadmap item #10).

## 6. Author-facing surface

Extend `templates/author/submissions.html` (added in roadmap item #10): for any paper with `status == 'ACCEPTED'`, render a small upload form (file input + "I agree to transfer copyright" checkbox + submit) posting to `/submission/{id}/camera-ready`. For `status == 'CAMERA_READY_SUBMITTED'`, render a short confirmation line instead ("Camera-ready submitted on `<date>`") reading `copyrightTransferAgreedAt` off the camera-ready-flagged version.

## 7. Testing

- `SubmissionServiceTest`: happy path (status transitions to `CAMERA_READY_SUBMITTED`, version flagged, timestamp set); wrong-status rejection (e.g. paper still `SUBMITTED` or `UNDER_REVIEW`); missing-copyright-agreement rejection; non-owner/non-admin rejection — mirroring the existing test shapes for `uploadRevision`.
- `ProceedingsServiceTest` (create if it doesn't already exist — verify at implementation time): confirm `generateProceedings`/`exportBibTeX` only include `CAMERA_READY_SUBMITTED` papers and pull the camera-ready-flagged version specifically, not merely the last one in the list (construct a paper with a later, non-camera-ready version appended after the camera-ready one, and assert the camera-ready one is what's selected).
- `SubmissionRestControllerTest` (or equivalent): new endpoint's happy path, 403, and 400 cases.
- `AdminDecisionViewControllerTest`: new `cameraReadyStatus` view, admin-sees-all vs. chair-sees-own-conference-only.
- Manual verification: full dev-profile boot + authenticated curl walk (submit → accept → camera-ready upload → proceedings generation), following the same real-login pattern established in roadmap item #10 (now that seed passwords actually work).

## 8. Open questions resolved during brainstorming

- **Is the camera-ready step required before proceedings generation, or optional/skippable?** Required — `ProceedingsService` now strictly requires `CAMERA_READY_SUBMITTED`, matching real conference-tool practice.
- **How is copyright-transfer acknowledgment recorded?** Checkbox + timestamp directly on `PaperVersion`, no separate entity.
- **Is there chair-facing visibility into camera-ready completion?** Yes — a new admin list view, reusing the existing admin template style rather than building new UI infrastructure.
