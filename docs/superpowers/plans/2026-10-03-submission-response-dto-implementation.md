# Submission REST Response DTO Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop `SubmissionRestController` from returning raw `Paper` JPA entities, which recurse infinitely through two bidirectional JPA relationships when serialized to JSON.

**Architecture:** Add four response DTOs (`PaperResponseDto`, `ConferenceSummaryDto`, `PaperVersionDto`, `PaperAuthorDto`) to `org.confcms.cms.submission.dto`, each with a static `from(...)` factory. Wire all six `SubmissionRestController` endpoints to map through `PaperResponseDto.from(...)` before returning.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Jackson 3 (`tools.jackson`), JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-03-submission-rest-response-dto-design.md`

---

### Task 1: PaperAuthorDto

**Files:**
- Create: `src/main/java/org/confcms/cms/submission/dto/PaperAuthorDto.java`
- Test: `src/test/java/org/confcms/cms/submission/dto/PaperAuthorDtoTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.confcms.cms.submission.dto;

import org.confcms.cms.submission.domain.PaperAuthor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaperAuthorDtoTest {

    @Test
    void fromMapsAllFieldsFromPaperAuthor() {
        PaperAuthor author = new PaperAuthor();
        author.setId(5L);
        author.setFullName("Ada Lovelace");
        author.setEmail("ada@example.com");
        author.setAffiliation("Analytical Engines Inc");
        author.setPresenter(true);

        PaperAuthorDto dto = PaperAuthorDto.from(author);

        assertThat(dto.getId()).isEqualTo(5L);
        assertThat(dto.getFullName()).isEqualTo("Ada Lovelace");
        assertThat(dto.getEmail()).isEqualTo("ada@example.com");
        assertThat(dto.getAffiliation()).isEqualTo("Analytical Engines Inc");
        assertThat(dto.isPresenter()).isTrue();
    }

    @Test
    void fromListMapsEachAuthorInOrder() {
        PaperAuthor first = new PaperAuthor();
        first.setFullName("Ada Lovelace");
        PaperAuthor second = new PaperAuthor();
        second.setFullName("Grace Hopper");

        List<PaperAuthorDto> dtos = PaperAuthorDto.from(List.of(first, second));

        assertThat(dtos).extracting(PaperAuthorDto::getFullName)
                .containsExactly("Ada Lovelace", "Grace Hopper");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.submission.dto.PaperAuthorDtoTest"`
Expected: FAIL (compile error — `PaperAuthorDto` does not exist)

- [ ] **Step 3: Write minimal implementation**

```java
package org.confcms.cms.submission.dto;

import org.confcms.cms.submission.domain.PaperAuthor;
import lombok.Getter;

import java.util.List;

@Getter
public class PaperAuthorDto {

    private final Long id;
    private final String fullName;
    private final String email;
    private final String affiliation;
    private final boolean presenter;

    private PaperAuthorDto(Long id, String fullName, String email, String affiliation, boolean presenter) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.affiliation = affiliation;
        this.presenter = presenter;
    }

    public static PaperAuthorDto from(PaperAuthor author) {
        return new PaperAuthorDto(
                author.getId(),
                author.getFullName(),
                author.getEmail(),
                author.getAffiliation(),
                author.isPresenter()
        );
    }

    public static List<PaperAuthorDto> from(List<PaperAuthor> authors) {
        return authors.stream().map(PaperAuthorDto::from).toList();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.submission.dto.PaperAuthorDtoTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/submission/dto/PaperAuthorDto.java src/test/java/org/confcms/cms/submission/dto/PaperAuthorDtoTest.java
git commit -m "feat: add PaperAuthorDto for safe API serialization"
```

---

### Task 2: ConferenceSummaryDto

**Files:**
- Create: `src/main/java/org/confcms/cms/submission/dto/ConferenceSummaryDto.java`
- Test: `src/test/java/org/confcms/cms/submission/dto/ConferenceSummaryDtoTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.confcms.cms.submission.dto;

import org.confcms.cms.domain.Conference;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ConferenceSummaryDtoTest {

    @Test
    void fromMapsIdTitleAndDatesButNotSubThemes() {
        Conference conference = new Conference();
        conference.setId(10L);
        conference.setTitle("Research Conference 2026");
        conference.setStartDate(LocalDate.of(2026, 11, 1));
        conference.setEndDate(LocalDate.of(2026, 11, 3));

        ConferenceSummaryDto dto = ConferenceSummaryDto.from(conference);

        assertThat(dto.getId()).isEqualTo(10L);
        assertThat(dto.getTitle()).isEqualTo("Research Conference 2026");
        assertThat(dto.getStartDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(dto.getEndDate()).isEqualTo(LocalDate.of(2026, 11, 3));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.submission.dto.ConferenceSummaryDtoTest"`
Expected: FAIL (compile error — `ConferenceSummaryDto` does not exist)

- [ ] **Step 3: Write minimal implementation**

```java
package org.confcms.cms.submission.dto;

import org.confcms.cms.domain.Conference;
import lombok.Getter;

import java.time.LocalDate;

@Getter
public class ConferenceSummaryDto {

    private final Long id;
    private final String title;
    private final LocalDate startDate;
    private final LocalDate endDate;

    private ConferenceSummaryDto(Long id, String title, LocalDate startDate, LocalDate endDate) {
        this.id = id;
        this.title = title;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    public static ConferenceSummaryDto from(Conference conference) {
        return new ConferenceSummaryDto(
                conference.getId(),
                conference.getTitle(),
                conference.getStartDate(),
                conference.getEndDate()
        );
    }
}
```

Note: deliberately no `subThemes` field — this is what breaks the `Conference.subThemes` ↔ `SubTheme.conference` cycle.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.submission.dto.ConferenceSummaryDtoTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/submission/dto/ConferenceSummaryDto.java src/test/java/org/confcms/cms/submission/dto/ConferenceSummaryDtoTest.java
git commit -m "feat: add ConferenceSummaryDto for safe API serialization"
```

---

### Task 3: PaperVersionDto

**Files:**
- Create: `src/main/java/org/confcms/cms/submission/dto/PaperVersionDto.java`
- Test: `src/test/java/org/confcms/cms/submission/dto/PaperVersionDtoTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.confcms.cms.submission.dto;

import org.confcms.cms.submission.domain.PaperVersion;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaperVersionDtoTest {

    @Test
    void fromMapsFieldsButExcludesFilePathAndPlagiarismFields() {
        PaperVersion version = new PaperVersion();
        version.setId(3L);
        version.setVersionNumber(2);
        version.setOriginalFilename("paper-v2.pdf");
        version.setFilePath("/var/app/uploads/uuid_paper-v2.pdf");
        version.setPossibleDuplicate(true);
        version.setCameraReady(true);
        version.setPlagiarismScore(12.5);
        version.setPlagiarismNote("Checked with Turnitin");
        Instant agreedAt = Instant.parse("2026-10-01T12:00:00Z");
        version.setCopyrightTransferAgreedAt(agreedAt);

        PaperVersionDto dto = PaperVersionDto.from(version);

        assertThat(dto.getId()).isEqualTo(3L);
        assertThat(dto.getVersionNumber()).isEqualTo(2);
        assertThat(dto.getOriginalFilename()).isEqualTo("paper-v2.pdf");
        assertThat(dto.isPossibleDuplicate()).isTrue();
        assertThat(dto.isCameraReady()).isTrue();
        assertThat(dto.getCopyrightTransferAgreedAt()).isEqualTo(agreedAt);
    }

    @Test
    void fromListMapsEachVersionInOrder() {
        PaperVersion v1 = new PaperVersion();
        v1.setVersionNumber(1);
        PaperVersion v2 = new PaperVersion();
        v2.setVersionNumber(2);

        List<PaperVersionDto> dtos = PaperVersionDto.from(List.of(v1, v2));

        assertThat(dtos).extracting(PaperVersionDto::getVersionNumber).containsExactly(1, 2);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.submission.dto.PaperVersionDtoTest"`
Expected: FAIL (compile error — `PaperVersionDto` does not exist)

- [ ] **Step 3: Write minimal implementation**

```java
package org.confcms.cms.submission.dto;

import org.confcms.cms.submission.domain.PaperVersion;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
public class PaperVersionDto {

    private final Long id;
    private final Integer versionNumber;
    private final String originalFilename;
    private final boolean possibleDuplicate;
    private final boolean cameraReady;
    private final Instant copyrightTransferAgreedAt;

    private PaperVersionDto(Long id, Integer versionNumber, String originalFilename,
                             boolean possibleDuplicate, boolean cameraReady,
                             Instant copyrightTransferAgreedAt) {
        this.id = id;
        this.versionNumber = versionNumber;
        this.originalFilename = originalFilename;
        this.possibleDuplicate = possibleDuplicate;
        this.cameraReady = cameraReady;
        this.copyrightTransferAgreedAt = copyrightTransferAgreedAt;
    }

    public static PaperVersionDto from(PaperVersion version) {
        return new PaperVersionDto(
                version.getId(),
                version.getVersionNumber(),
                version.getOriginalFilename(),
                version.isPossibleDuplicate(),
                version.isCameraReady(),
                version.getCopyrightTransferAgreedAt()
        );
    }

    public static List<PaperVersionDto> from(List<PaperVersion> versions) {
        return versions.stream().map(PaperVersionDto::from).toList();
    }
}
```

Note: deliberately no `filePath` (absolute server filesystem path), `plagiarismScore`, or `plagiarismNote` (admin/committee-internal) fields — per the spec's allow-list.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.submission.dto.PaperVersionDtoTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/submission/dto/PaperVersionDto.java src/test/java/org/confcms/cms/submission/dto/PaperVersionDtoTest.java
git commit -m "feat: add PaperVersionDto excluding server-internal fields"
```

---

### Task 4: PaperResponseDto

**Files:**
- Create: `src/main/java/org/confcms/cms/submission/dto/PaperResponseDto.java`
- Test: `src/test/java/org/confcms/cms/submission/dto/PaperResponseDtoTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.confcms.cms.submission.dto;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperAuthor;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.domain.PaperVersion;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PaperResponseDtoTest {

    @Test
    void fromMapsTopLevelFieldsConferenceVersionsAndAuthors() {
        Conference conference = new Conference();
        conference.setId(10L);
        conference.setTitle("Research Conference 2026");
        conference.setStartDate(LocalDate.of(2026, 11, 1));
        conference.setEndDate(LocalDate.of(2026, 11, 3));

        Paper paper = new Paper();
        paper.setId(1L);
        paper.setTitle("A Great Paper");
        paper.setAbstractText("An abstract.");
        paper.setTrack("Machine Learning");
        paper.setStatus(PaperStatus.SUBMITTED);
        paper.setConference(conference);

        PaperVersion version = new PaperVersion();
        version.setVersionNumber(1);
        paper.getVersions().add(version);

        PaperAuthor author = new PaperAuthor();
        author.setFullName("Ada Lovelace");
        paper.getAuthors().add(author);

        PaperResponseDto dto = PaperResponseDto.from(paper);

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getTitle()).isEqualTo("A Great Paper");
        assertThat(dto.getAbstractText()).isEqualTo("An abstract.");
        assertThat(dto.getTrack()).isEqualTo("Machine Learning");
        assertThat(dto.getStatus()).isEqualTo("SUBMITTED");
        assertThat(dto.getConference().getId()).isEqualTo(10L);
        assertThat(dto.getVersions()).hasSize(1);
        assertThat(dto.getAuthors()).extracting(PaperAuthorDto::getFullName).containsExactly("Ada Lovelace");
    }

    @Test
    void fromListMapsEachPaperInOrder() {
        Paper first = new Paper();
        first.setTitle("First");
        first.setConference(new Conference());
        Paper second = new Paper();
        second.setTitle("Second");
        second.setConference(new Conference());

        List<PaperResponseDto> dtos = PaperResponseDto.from(List.of(first, second));

        assertThat(dtos).extracting(PaperResponseDto::getTitle).containsExactly("First", "Second");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.submission.dto.PaperResponseDtoTest"`
Expected: FAIL (compile error — `PaperResponseDto` does not exist)

- [ ] **Step 3: Write minimal implementation**

```java
package org.confcms.cms.submission.dto;

import org.confcms.cms.submission.domain.Paper;
import lombok.Getter;

import java.util.List;

@Getter
public class PaperResponseDto {

    private final Long id;
    private final String title;
    private final String abstractText;
    private final String track;
    private final String status;
    private final ConferenceSummaryDto conference;
    private final List<PaperVersionDto> versions;
    private final List<PaperAuthorDto> authors;

    private PaperResponseDto(Long id, String title, String abstractText, String track, String status,
                              ConferenceSummaryDto conference, List<PaperVersionDto> versions,
                              List<PaperAuthorDto> authors) {
        this.id = id;
        this.title = title;
        this.abstractText = abstractText;
        this.track = track;
        this.status = status;
        this.conference = conference;
        this.versions = versions;
        this.authors = authors;
    }

    public static PaperResponseDto from(Paper paper) {
        return new PaperResponseDto(
                paper.getId(),
                paper.getTitle(),
                paper.getAbstractText(),
                paper.getTrack(),
                paper.getStatus() != null ? paper.getStatus().name() : null,
                ConferenceSummaryDto.from(paper.getConference()),
                PaperVersionDto.from(paper.getVersions()),
                PaperAuthorDto.from(paper.getAuthors())
        );
    }

    public static List<PaperResponseDto> from(List<Paper> papers) {
        return papers.stream().map(PaperResponseDto::from).toList();
    }
}
```

Note: no `submitter`/`User` field anywhere in this DTO — the caller already knows who they are (it's their own paper), so `User`/`passwordHash` can never reach this response.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.submission.dto.PaperResponseDtoTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/submission/dto/PaperResponseDto.java src/test/java/org/confcms/cms/submission/dto/PaperResponseDtoTest.java
git commit -m "feat: add PaperResponseDto composing conference/versions/authors"
```

---

### Task 5: Wire SubmissionRestController to the new DTOs

**Files:**
- Modify: `src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java`
- Modify: `src/test/java/org/confcms/cms/web/controller/SubmissionRestControllerTest.java`

The controller currently has six places returning `ResponseEntity.ok(saved)` or `ResponseEntity.ok(submissionService.getPapersBySubmitter(user))` where `saved`/the list is a raw `Paper`/`List<Paper>`. This task updates all six call sites and the two existing tests that assert against the raw entity.

- [ ] **Step 1: Update the two existing tests to assert against the DTO instead of the raw entity**

In `src/test/java/org/confcms/cms/web/controller/SubmissionRestControllerTest.java`, replace the existing `submitPaperAcceptsAuthorsJsonWithCorrectPresenterKey` test body (the only one asserting on `response.getBody()`) with:

```java
    @Test
    void submitPaperAcceptsAuthorsJsonWithCorrectPresenterKey() {
        String authorsJson = "[{\"fullName\":\"Ada Lovelace\",\"email\":\"ada@example.com\",\"affiliation\":\"Analytical Engines Inc\",\"presenter\":true}]";
        Paper saved = new Paper();
        saved.setId(42L);
        saved.setTitle("Title");
        saved.setConference(new org.confcms.cms.domain.Conference());
        when(submissionService.submitPaper(any(User.class), any(), any(), any(), any(), any())).thenReturn(saved);

        ResponseEntity<?> response = controller.submitPaper("Title", "Abstract", "track", authorsJson, file);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isInstanceOf(org.confcms.cms.submission.dto.PaperResponseDto.class);
        assertThat(((org.confcms.cms.submission.dto.PaperResponseDto) response.getBody()).getId()).isEqualTo(42L);
    }
```

The `saved.setConference(new Conference())` line is required: `PaperResponseDto.from` calls `ConferenceSummaryDto.from(paper.getConference())`, and a `Paper` with a null `conference` would NPE here — the previous test only worked because it never touched the conference field.

- [ ] **Step 2: Run tests to verify the updated test fails against the current controller**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.SubmissionRestControllerTest"`
Expected: FAIL (`response.getBody()` is still the raw `Paper`, not a `PaperResponseDto`)

- [ ] **Step 3: Wire the controller's six return points through the new DTOs**

In `src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java`:

Add the import:

```java
import org.confcms.cms.submission.dto.PaperResponseDto;
```

Change each of the six return statements:

```java
        Paper saved = submissionService.submitPaper(user, title, abstractText, track, file, authors);
        return ResponseEntity.ok(saved);
```
becomes
```java
        Paper saved = submissionService.submitPaper(user, title, abstractText, track, file, authors);
        return ResponseEntity.ok(PaperResponseDto.from(saved));
```

```java
        return ResponseEntity.ok(submissionService.getPapersBySubmitter(user));
```
becomes
```java
        return ResponseEntity.ok(PaperResponseDto.from(submissionService.getPapersBySubmitter(user)));
```

In `uploadVersion`:
```java
            Paper saved = submissionService.uploadNewVersion(user, id, file);
            return ResponseEntity.ok(saved);
```
becomes
```java
            Paper saved = submissionService.uploadNewVersion(user, id, file);
            return ResponseEntity.ok(PaperResponseDto.from(saved));
```

In `uploadRevision`:
```java
            Paper saved = submissionService.uploadRevision(user, id, file);
            return ResponseEntity.ok(saved);
```
becomes
```java
            Paper saved = submissionService.uploadRevision(user, id, file);
            return ResponseEntity.ok(PaperResponseDto.from(saved));
```

In `uploadCameraReady`:
```java
            Paper saved = submissionService.uploadCameraReady(user, id, file, copyrightAgreed);
            return ResponseEntity.ok(saved);
```
becomes
```java
            Paper saved = submissionService.uploadCameraReady(user, id, file, copyrightAgreed);
            return ResponseEntity.ok(PaperResponseDto.from(saved));
```

In `withdraw`:
```java
            Paper saved = submissionService.withdrawPaper(user, id);
            return ResponseEntity.ok(saved);
```
becomes
```java
            Paper saved = submissionService.withdrawPaper(user, id);
            return ResponseEntity.ok(PaperResponseDto.from(saved));
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.SubmissionRestControllerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java src/test/java/org/confcms/cms/web/controller/SubmissionRestControllerTest.java
git commit -m "fix: return PaperResponseDto instead of raw Paper entity from SubmissionRestController"
```

---

### Task 6: Regression test reproducing both original cycles through the real JsonMapper

**Files:**
- Create: `src/test/java/org/confcms/cms/web/controller/SubmissionRestControllerSerializationTest.java`

This is the test that actually proves the bug is fixed: it reconstructs both bidirectional cycles that caused the original infinite recursion (`Conference.subThemes` ↔ `SubTheme.conference`, and `Paper.authors` ↔ `PaperAuthor.paper`), serializes the resulting DTO through a real Jackson `JsonMapper` (the same kind of mapper `SubmissionRestController` already builds for request parsing), and asserts the output is a small, flat, finite JSON document with no `passwordHash` or `filePath` keys.

- [ ] **Step 1: Write the test**

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.SubTheme;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperAuthor;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.domain.PaperVersion;
import org.confcms.cms.submission.dto.PaperResponseDto;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SubmissionRestControllerSerializationTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void paperResponseDtoSerializesFiniteJsonWithNoCycleAndNoSensitiveFields() {
        // Reconstruct cycle #1: Conference.subThemes <-> SubTheme.conference
        Conference conference = new Conference();
        conference.setId(10L);
        conference.setTitle("Research Conference 2026");
        conference.setStartDate(LocalDate.of(2026, 11, 1));
        conference.setEndDate(LocalDate.of(2026, 11, 3));
        SubTheme subTheme = new SubTheme();
        subTheme.setName("Machine Learning");
        subTheme.setConference(conference);
        conference.getSubThemes().add(subTheme);

        Paper paper = new Paper();
        paper.setId(1L);
        paper.setTitle("A Great Paper");
        paper.setAbstractText("An abstract.");
        paper.setTrack("Machine Learning");
        paper.setStatus(PaperStatus.SUBMITTED);
        paper.setConference(conference);

        PaperVersion version = new PaperVersion();
        version.setVersionNumber(1);
        version.setOriginalFilename("paper.pdf");
        version.setFilePath("/var/app/uploads/uuid_paper.pdf");
        version.setPaper(paper);
        paper.getVersions().add(version);

        // Reconstruct cycle #2: Paper.authors <-> PaperAuthor.paper
        PaperAuthor author = new PaperAuthor();
        author.setFullName("Ada Lovelace");
        author.setEmail("ada@example.com");
        author.setAffiliation("Analytical Engines Inc");
        author.setPaper(paper);
        paper.getAuthors().add(author);

        PaperResponseDto dto = PaperResponseDto.from(paper);

        String json = jsonMapper.writeValueAsString(dto);

        assertThat(json).doesNotContain("passwordHash");
        assertThat(json).doesNotContain("filePath");
        assertThat(json).doesNotContain("subThemes");
        assertThat(json).contains("\"title\":\"A Great Paper\"");
        assertThat(json).contains("\"fullName\":\"Ada Lovelace\"");
        // A real recursive response from the old bug was ~34-140KB; the fixed shape is tiny.
        assertThat(json.length()).isLessThan(2000);
    }
}
```

- [ ] **Step 2: Run test to verify it fails before Task 1-5 land**

(If run after Tasks 1-5 are already complete, this step is only meaningful if you temporarily revert — otherwise skip straight to Step 3. This task is written to run last in the plan specifically so it validates the already-completed fix.)

Run: `./gradlew test --tests "org.confcms.cms.web.controller.SubmissionRestControllerSerializationTest"`
Expected: PASS (the DTOs from Tasks 1-4 and the controller wiring from Task 5 already prevent the cycle)

- [ ] **Step 3: Confirm it passes**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.SubmissionRestControllerSerializationTest"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/test/java/org/confcms/cms/web/controller/SubmissionRestControllerSerializationTest.java
git commit -m "test: add regression test reproducing both original Jackson recursion cycles"
```

---

### Task 7: Full suite and finish

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew test --rerun`
Expected: BUILD SUCCESSFUL, 0 failures across the full suite (265 pre-existing + 10 new from this plan: 2 each for PaperAuthorDto/ConferenceSummaryDto/PaperVersionDto/PaperResponseDto's `from`/`from-list` tests, plus the serialization regression test — exact new count depends on how many `@Test` methods were written per task above)

- [ ] **Step 2: Delete the two now-fixed items from the deferred-findings tracking file**

In `docs/superpowers/deferred-findings.md`, remove:
- The `**Investigated during manual verification...**` bullet under `## From: Access Audit Log + Duplicate-Paper Detection + GeoLite2 (roadmap #8)` (the `Conference.subThemes`/`SubTheme.conference` cycle finding).
- The `**M2:**` bullet under `## From: Spring Boot 3.5.8 → 4.1.1 Migration` (the `Paper.authors`/`PaperAuthor.paper` cycle finding).

- [ ] **Step 3: Commit the tracking-file update**

```bash
git add docs/superpowers/deferred-findings.md
git commit -m "docs: remove the 2 fixed Jackson-cycle findings from the tracking file"
```

- [ ] **Step 4: Hand off via finishing-a-development-branch**

Use the `superpowers:finishing-a-development-branch` skill to verify tests one more time and present the merge/PR/keep-as-is menu.
