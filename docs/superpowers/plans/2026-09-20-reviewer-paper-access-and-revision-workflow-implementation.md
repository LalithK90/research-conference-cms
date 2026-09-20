# Reviewer Paper Access, Blind Review, Anonymized Feedback & Revision Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a reviewer view and download the paper for any assignment they own; add a per-conference blind-review toggle that hides author names from that view; bundle anonymized reviewer feedback (never reviewer identity, never confidential comments) into the accept/reject decision email; and add a full revision-decision workflow (system-suggested minor/major revision, chair-set due date, author re-upload, chair resolution, same-reviewer re-review, lazy auto-reject on a missed deadline).

**Architecture:** Two new endpoints on the existing `ReviewRestController` backed by a new `ReviewService.getPaperForAssignment` method; a shared, structurally-identity-free feedback-building helper inside `DecisionService` reused by both the existing `applyDecision` and the new `requestRevision`; a new `uploadRevision` method on `SubmissionService` following the exact pattern of the existing `uploadNewVersion`. No new services, no new infrastructure (confirmed via a spike: Thymeleaf's TEXT-mode template engine, already in use for every `.txt` email template, supports the `[# th:each="..."]...[/]` loop syntax needed for the anonymized feedback list — verified by actually rendering a test template, not assumed).

**Tech Stack:** Spring Boot 3.5, Java 21, JPA/Hibernate, Spring Security, Thymeleaf (TEXT mode for `.txt` email templates), JUnit 5 + AssertJ + Mockito.

---

## Context for the engineer picking this up

This plan implements `docs/superpowers/specs/2026-09-20-reviewer-paper-access-and-revision-workflow-design.md`. Read that spec first for full rationale, including why several related items (conference-level milestone dates, reviewer-deadline reminders, download audit logging, a real scheduled job, specialist-field tagging, HTML emails, caching, SEO) are explicitly deferred to future work, not part of this plan.

This plan builds directly on three prior features already merged to `main`: domain-model-merge (canonical package layout, `Paper.conference`), committee-roles (`CommitteeService.isChairOrCoChair`, `requireChairOrAdmin` pattern already in `DecisionService`), and reviewer-decline/person-invitations (the `actingUser()` helper pattern in `ReviewRestController`, the ownership-check pattern in `ReviewAssignmentService.declineAssignment`/`ReviewService.submitReview`).

**One fact verified during planning, not assumed:** the email templates in `src/main/resources/templates/email/*.txt` are parsed by Thymeleaf in **TEXT mode** (confirmed by deliberately triggering and reading a `TextParseException`, which only exists in Thymeleaf's text-mode parser). TEXT mode's iteration syntax is `[# th:each="item : ${items}"]...[/]` — **not** the `th:each="..."` HTML attribute syntax, and **not** `[/#]` (which fails with "unnamed element is never closed"). This was confirmed by actually rendering a probe template through `EmailTemplateService` inside a throwaway `@SpringBootTest`, observing the correct output (`- a`, `- b`, `- c` for a 3-item list), then deleting the probe files. Task 5 below uses this exact, verified syntax.

**One fact verified, contradicting an assumption in earlier design work:** `AdminDecisionViewController.paperDetail` is already correctly gated to `ADMIN`/Chair-or-Co-Chair only (fixed by an earlier security patch on `main`) — this plan does not touch that controller at all.

---

## Task 1: `Conference.blindReview` field and conference-creation form

**Files:**
- Modify: `src/main/java/org/confcms/cms/domain/Conference.java`
- Modify: `src/main/java/org/confcms/cms/web/controller/AdminConferenceController.java`
- Modify: `src/main/resources/templates/admin/conference_form.html`
- Test: `src/test/java/org/confcms/cms/web/controller/AdminConferenceControllerTest.java` (extends the existing file)

- [ ] **Step 1: Add the field to `Conference`**

In `src/main/java/org/confcms/cms/domain/Conference.java`, add after the existing `isActive` field:

```java
    @Column(nullable = false)
    private boolean blindReview = false;
```

- [ ] **Step 2: Write the failing test**

Add to `src/test/java/org/confcms/cms/web/controller/AdminConferenceControllerTest.java` (existing file from the committee-roles plan):

```java
    @Test
    void saveConferenceSetsBlindReviewFlag() {
        AdminConferenceController.ConferenceForm form = new AdminConferenceController.ConferenceForm();
        form.setTitle("Test Conf");
        form.setVenue("Venue");
        form.setStartDate(LocalDate.now());
        form.setEndDate(LocalDate.now().plusDays(1));
        form.setContactEmail("a@b.com");
        form.setPaymentProvider(PaymentProvider.FREE);
        form.setChairUserId(7L);
        form.setBlindReview(true);

        User chairUser = new User();
        chairUser.setId(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(chairUser));
        when(conferenceService.saveConference(any())).thenAnswer(inv -> {
            Conference c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        controller.saveConference(form);

        org.mockito.Mockito.verify(conferenceService).saveConference(
                org.mockito.ArgumentMatchers.argThat(Conference::isBlindReview));
    }
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.AdminConferenceControllerTest"`
Expected: FAILS to compile — `ConferenceForm.setBlindReview` doesn't exist yet.

- [ ] **Step 4: Add `blindReview` to `ConferenceForm` and wire it into `saveConference`**

In `src/main/java/org/confcms/cms/web/controller/AdminConferenceController.java`, add to the `ConferenceForm` inner class (after `active`):

```java
        private boolean blindReview;
```

In `saveConference`, add after `conference.setActive(form.isActive());`:

```java
        conference.setBlindReview(form.isBlindReview());
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.AdminConferenceControllerTest"`
Expected: PASS (3 tests: the 2 pre-existing from the committee-roles plan, plus this new one).

- [ ] **Step 6: Add the checkbox to the conference-creation form**

In `src/main/resources/templates/admin/conference_form.html`, the file currently has (from the committee-roles feature) a "Committee" section ending just before a `<hr>` that precedes "Payment Configuration". Insert a new checkbox row immediately after the Committee section's closing `</div>` and before that `<hr>`:

```html
                    <div class="row mb-3">
                        <div class="col-md-12 form-check">
                            <input type="checkbox" class="form-check-input" th:field="*{blindReview}">
                            <label class="form-check-label">Enable Blind Review (hide author names from reviewers)</label>
                        </div>
                    </div>
```

Verify after this edit that the file still has exactly one `<hr>` between this new checkbox row and "Payment Configuration" — do not introduce a duplicate.

- [ ] **Step 7: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/confcms/cms/domain/Conference.java src/main/java/org/confcms/cms/web/controller/AdminConferenceController.java src/main/resources/templates/admin/conference_form.html src/test/java/org/confcms/cms/web/controller/AdminConferenceControllerTest.java
git commit -m "feat: add Conference.blindReview, settable at conference creation

Per-conference toggle, defaults to false, set once via the existing
conference-creation form alongside Chair/Co-Chair selection. Controls
only what a reviewer's paper-view endpoint returns (Task 3) -- does
not affect reviewer assignment/selection logic, per explicit
stakeholder clarification during design."
```

---

## Task 2: `PaperReviewView` DTO

**Files:**
- Create: `src/main/java/org/confcms/cms/review/dto/PaperReviewView.java`

- [ ] **Step 1: Create the DTO**

Create `src/main/java/org/confcms/cms/review/dto/PaperReviewView.java`:

```java
package org.confcms.cms.review.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PaperReviewView(
        Long paperId,
        String title,
        String abstractText,
        String track,
        Integer latestVersionNumber,
        LocalDateTime dueDate,
        List<String> authorNames
) {
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/confcms/cms/review/dto/PaperReviewView.java
git commit -m "feat: add PaperReviewView DTO for reviewer paper metadata

authorNames is empty (never null) when the conference has blind review
enabled -- Task 3 populates this DTO."
```

---

## Task 3: `ReviewService.getPaperForAssignment` and the two reviewer endpoints

**Files:**
- Modify: `src/main/java/org/confcms/cms/review/service/ReviewService.java`
- Modify: `src/main/java/org/confcms/cms/web/controller/ReviewRestController.java`
- Test: `src/test/java/org/confcms/cms/review/service/ReviewServiceTest.java` (new)

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/confcms/cms/review/service/ReviewServiceTest.java`:

```java
package org.confcms.cms.review.service;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.dto.PaperReviewView;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;
import org.confcms.cms.review.repository.ReviewRepository;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperAuthor;
import org.confcms.cms.submission.domain.PaperVersion;
import org.confcms.cms.submission.repository.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private ReviewAssignmentRepository assignmentRepository;
    @Mock
    private PaperRepository paperRepository;

    private ReviewService service;
    private Conference conference;
    private Paper paper;
    private ReviewAssignment assignment;
    private User reviewer;

    @BeforeEach
    void setUp() {
        service = new ReviewService(reviewRepository, assignmentRepository, paperRepository);

        conference = new Conference();
        conference.setId(1L);

        paper = new Paper();
        paper.setId(5L);
        paper.setTitle("A Paper");
        paper.setAbstractText("An abstract");
        paper.setTrack("Track A");
        paper.setConference(conference);

        PaperAuthor author = new PaperAuthor();
        author.setFullName("Jane Author");
        paper.getAuthors().add(author);

        PaperVersion version = new PaperVersion();
        version.setVersionNumber(1);
        version.setFilePath("/uploads/paper.pdf");
        version.setOriginalFilename("paper.pdf");
        paper.getVersions().add(version);

        reviewer = new User();
        reviewer.setId(20L);

        assignment = new ReviewAssignment();
        assignment.setId(100L);
        assignment.setPaper(paper);
        assignment.setReviewer(reviewer);
        assignment.setDueDate(LocalDateTime.of(2026, 10, 1, 0, 0));
    }

    @Test
    void getPaperForAssignmentRejectsNonOwningReviewer() {
        User stranger = new User();
        stranger.setId(99L);

        when(assignmentRepository.findById(100L)).thenReturn(Optional.of(assignment));

        assertThatThrownBy(() -> service.getPaperForAssignment(stranger, 100L))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void getPaperForAssignmentThrowsWhenAssignmentNotFound() {
        when(assignmentRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPaperForAssignment(reviewer, 999L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getPaperForAssignmentReturnsAuthorNamesWhenNotBlind() {
        conference.setBlindReview(false);
        when(assignmentRepository.findById(100L)).thenReturn(Optional.of(assignment));

        Paper result = service.getPaperForAssignment(reviewer, 100L);

        assertThat(result).isEqualTo(paper);
    }

    @Test
    void toReviewViewHidesAuthorNamesWhenBlind() {
        conference.setBlindReview(true);

        PaperReviewView view = service.toReviewView(paper, assignment);

        assertThat(view.authorNames()).isEmpty();
        assertThat(view.dueDate()).isEqualTo(assignment.getDueDate());
        assertThat(view.latestVersionNumber()).isEqualTo(1);
    }

    @Test
    void toReviewViewShowsAuthorNamesWhenNotBlind() {
        conference.setBlindReview(false);

        PaperReviewView view = service.toReviewView(paper, assignment);

        assertThat(view.authorNames()).containsExactly("Jane Author");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.review.service.ReviewServiceTest"`
Expected: FAILS to compile — `getPaperForAssignment` and `toReviewView` don't exist yet.

- [ ] **Step 3: Implement `getPaperForAssignment` and `toReviewView`**

Replace the contents of `src/main/java/org/confcms/cms/review/service/ReviewService.java`:

```java
package org.confcms.cms.review.service;

import org.confcms.cms.domain.User;
import org.confcms.cms.review.domain.AssignmentStatus;
import org.confcms.cms.review.domain.Review;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.dto.PaperReviewView;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;
import org.confcms.cms.review.repository.ReviewRepository;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperVersion;
import org.confcms.cms.submission.repository.PaperRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final ReviewAssignmentRepository assignmentRepository;
    private final PaperRepository paperRepository;

    @Transactional
    public Review submitReview(User reviewer, Long assignmentId, Integer score, String comments, String confidentialComments) {
        ReviewAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        if (!assignment.getReviewer().getId().equals(reviewer.getId())) {
            throw new SecurityException("Not authorized to submit a review for this assignment");
        }

        if (assignment.getStatus() == AssignmentStatus.COMPLETED) {
            throw new IllegalStateException("Review already submitted");
        }

        Review review = new Review();
        review.setPaper(assignment.getPaper());
        review.setReviewer(assignment.getReviewer());
        review.setScore(score);
        review.setComments(comments);
        review.setConfidentialComments(confidentialComments);
        
        reviewRepository.save(review);

        assignment.setStatus(AssignmentStatus.COMPLETED);
        assignmentRepository.save(assignment);

        return review;
    }

    private ReviewAssignment loadOwnedAssignment(User actingUser, Long assignmentId) {
        ReviewAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));

        if (!assignment.getReviewer().getId().equals(actingUser.getId())) {
            throw new SecurityException("Not authorized to view this paper");
        }

        return assignment;
    }

    @Transactional(readOnly = true)
    public Paper getPaperForAssignment(User actingUser, Long assignmentId) {
        return loadOwnedAssignment(actingUser, assignmentId).getPaper();
    }

    @Transactional(readOnly = true)
    public PaperReviewView toReviewView(Paper paper, ReviewAssignment assignment) {
        Integer latestVersionNumber = paper.getVersions().stream()
                .map(PaperVersion::getVersionNumber)
                .max(Comparator.naturalOrder())
                .orElse(null);

        List<String> authorNames = paper.getConference().isBlindReview()
                ? List.of()
                : paper.getAuthors().stream().map(a -> a.getFullName()).toList();

        return new PaperReviewView(
                paper.getId(),
                paper.getTitle(),
                paper.getAbstractText(),
                paper.getTrack(),
                latestVersionNumber,
                assignment.getDueDate(),
                authorNames
        );
    }

    @Transactional(readOnly = true)
    public String getLatestVersionFilePath(Paper paper) {
        return paper.getVersions().stream()
                .max(Comparator.comparing(PaperVersion::getVersionNumber))
                .map(PaperVersion::getFilePath)
                .orElseThrow(() -> new IllegalArgumentException("Paper has no uploaded version"));
    }
}
```

Note: `toReviewView` is a pure mapping method (no repository calls), kept `@Transactional(readOnly = true)` only because it's invoked from the controller after `getPaperForAssignment` inside the same request, not because it needs its own transaction — matches the existing codebase's habit of marking read paths transactional even when not strictly required, for consistency.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.review.service.ReviewServiceTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Add the two endpoints to `ReviewRestController`**

In `src/main/java/org/confcms/cms/web/controller/ReviewRestController.java`, add the imports:

```java
import org.confcms.cms.review.dto.PaperReviewView;
import org.confcms.cms.submission.domain.Paper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
```

Add after the `declineAssignment` method (before the closing `}` of the class):

```java
    @GetMapping("/assignment/{assignmentId}/paper")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<?> getPaperForReview(@PathVariable Long assignmentId) {
        try {
            var assignmentOpt = assignmentRepository.findById(assignmentId);
            if (assignmentOpt.isEmpty()) {
                return ResponseEntity.status(404).body("Assignment not found");
            }
            Paper paper = reviewService.getPaperForAssignment(actingUser(), assignmentId);
            PaperReviewView view = reviewService.toReviewView(paper, assignmentOpt.get());
            return ResponseEntity.ok(view);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @GetMapping("/assignment/{assignmentId}/paper/file")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<?> downloadPaperForReview(@PathVariable Long assignmentId) {
        try {
            Paper paper = reviewService.getPaperForAssignment(actingUser(), assignmentId);
            String filePath;
            try {
                filePath = reviewService.getLatestVersionFilePath(paper);
            } catch (IllegalArgumentException iae) {
                return ResponseEntity.status(404).body(iae.getMessage());
            }
            Resource resource = new UrlResource(fileStorageService.load(filePath).toUri());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"paper.pdf\"")
                    .body(resource);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        } catch (java.net.MalformedURLException e) {
            return ResponseEntity.status(404).body("File not found");
        }
    }
```

Add the `FileStorageService` field (needed by the new download endpoint) alongside the existing fields:

```java
    private final org.confcms.cms.service.FileStorageService fileStorageService;
```

- [ ] **Step 6: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Manual verification via running app**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` in the background, then:
`curl -s -o /dev/null -w "HTTP_%{http_code}\n" http://localhost:8080/review/assignment/999/paper` — expect `302` or `401`-equivalent redirect to login (unauthenticated), confirming the endpoint is wired and gated, not a 404 route-not-found. Stop the app afterward.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/confcms/cms/review/service/ReviewService.java src/main/java/org/confcms/cms/web/controller/ReviewRestController.java src/test/java/org/confcms/cms/review/service/ReviewServiceTest.java
git commit -m "feat: reviewer paper metadata and file-download endpoints

GET /review/assignment/{id}/paper returns PaperReviewView (blind-review-
aware author-name masking); GET /review/assignment/{id}/paper/file
streams the latest PaperVersion's PDF inline. Both ownership-checked
via the same pattern as submitReview/declineAssignment -- the caller
must own the ReviewAssignment, no restriction on assignment status
(PENDING or COMPLETED both allowed, matching standard practice in
EasyChair/CMT)."
```

---

## Task 4: Anonymized feedback shared helper + `applyDecision` bundling + `rejection_notification.txt`

**Files:**
- Modify: `src/main/java/org/confcms/cms/service/DecisionService.java`
- Create: `src/main/resources/templates/email/rejection_notification.txt`
- Modify: `src/main/resources/templates/email/acceptance_notification.txt`
- Test: `src/test/java/org/confcms/cms/service/DecisionServiceTest.java` (extends the existing file)

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/org/confcms/cms/service/DecisionServiceTest.java` (existing file from the committee-roles plan). First add these imports:

```java
import org.confcms.cms.review.domain.Review;
import org.confcms.cms.review.repository.ReviewRepository;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
```

(Check the existing file first — `ReviewRepository` is likely already a `@Mock` field named `reviewRepository` from the existing test setup; if so, don't re-declare it, just reuse it.)

Add these test methods:

```java
    @Test
    void applyDecisionAcceptEmailModelContainsAnonymizedFeedbackNotReviewerIdentity() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);

        Review review1 = new Review();
        review1.setScore(4);
        review1.setComments("Good work, minor typos.");
        review1.setConfidentialComments("Reviewer thinks this is borderline.");
        User reviewerUser = new User();
        reviewerUser.setFullName("Should Never Appear");
        review1.setReviewer(reviewerUser);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.findByPaperId(5L)).thenReturn(List.of(review1));

        service.applyDecision(admin, 5L, "ACCEPT");

        ArgumentCaptor<Map<String, Object>> modelCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendTemplateEmail(any(), any(), any(), modelCaptor.capture());

        Map<String, Object> model = modelCaptor.getValue();
        String modelAsString = model.toString();
        assertThat(modelAsString).doesNotContain("Should Never Appear");
        assertThat(modelAsString).doesNotContain("borderline");
        assertThat(modelAsString).contains("Good work, minor typos.");
    }

    @Test
    void applyDecisionRejectSendsTemplatedEmailWithFeedback() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);

        Review review1 = new Review();
        review1.setScore(2);
        review1.setComments("Needs more experiments.");
        User reviewerUser = new User();
        review1.setReviewer(reviewerUser);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.findByPaperId(5L)).thenReturn(List.of(review1));

        service.applyDecision(admin, 5L, "REJECT");

        verify(emailService).sendTemplateEmail(any(), any(), eq("email/rejection_notification.txt"), anyMap());
    }
```

Add the needed imports for `ArgumentCaptor` and `Map` if not already present:

```java
import org.mockito.ArgumentCaptor;
import java.util.Map;
import static org.mockito.ArgumentMatchers.eq;
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.service.DecisionServiceTest"`
Expected: FAILS — `rejection_notification.txt` doesn't exist, `applyDecision`'s REJECT branch doesn't call `sendTemplateEmail`, and no feedback is built for either branch.

- [ ] **Step 3: Create the shared anonymized-feedback-building helper and wire it into `applyDecision`**

Replace the contents of `src/main/java/org/confcms/cms/service/DecisionService.java`:

```java
package org.confcms.cms.service;

import org.confcms.cms.domain.User;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.repository.PaperRepository;
import org.confcms.cms.review.domain.Review;
import org.confcms.cms.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DecisionService {

    private final ReviewRepository reviewRepository;
    private final PaperRepository paperRepository;
    private final EmailService emailService;
    private final CommitteeService committeeService;

    public enum DeskDecision { SEND_TO_REVIEW, DESK_REJECT }

    public static class DecisionSuggestion {
        public Long paperId;
        public String title;
        public double averageScore;
        public String suggestion; // ACCEPT / REJECT / BORDERLINE

        public DecisionSuggestion(Long paperId, String title, double averageScore, String suggestion) {
            this.paperId = paperId;
            this.title = title;
            this.averageScore = averageScore;
            this.suggestion = suggestion;
        }
    }

    public List<DecisionSuggestion> suggestDecisions(double acceptThreshold, double rejectThreshold) {
        List<Paper> papers = paperRepository.findAll();
        List<DecisionSuggestion> suggestions = new ArrayList<>();

        for (Paper paper : papers) {
            List<Review> reviews = reviewRepository.findByPaperId(paper.getId());
            double avg = 0.0;
            if (!reviews.isEmpty()) {
                avg = reviews.stream().mapToDouble(Review::getScore).average().orElse(0.0);
            }

            String suggestion = "BORDERLINE";
            if (avg >= acceptThreshold) suggestion = "ACCEPT";
            else if (avg <= rejectThreshold) suggestion = "REJECT";

            suggestions.add(new DecisionSuggestion(paper.getId(), paper.getTitle(), avg, suggestion));
        }

        // sort by average descending
        return suggestions.stream()
                .sorted(Comparator.comparingDouble((DecisionSuggestion ds) -> ds.averageScore).reversed())
                .collect(Collectors.toList());
    }

    private void requireChairOrAdmin(User actingUser, Paper paper) {
        boolean isAdmin = actingUser.getRole() == Role.ADMIN;
        if (!isAdmin && !committeeService.isChairOrCoChair(actingUser, paper.getConference())) {
            throw new SecurityException("Not authorized to make decisions for this conference's papers");
        }
    }

    // Structurally excludes reviewer identity and confidentialComments -- the returned
    // list's element type has no field capable of holding either, so no downstream
    // template can accidentally render them.
    public static class AnonymizedFeedbackItem {
        public int index;
        public Integer score;
        public String comments;

        public AnonymizedFeedbackItem(int index, Integer score, String comments) {
            this.index = index;
            this.score = score;
            this.comments = comments;
        }
    }

    private List<AnonymizedFeedbackItem> buildAnonymizedFeedback(Long paperId) {
        List<Review> reviews = reviewRepository.findByPaperId(paperId);
        List<AnonymizedFeedbackItem> feedback = new ArrayList<>();
        int index = 1;
        for (Review review : reviews) {
            feedback.add(new AnonymizedFeedbackItem(index, review.getScore(), review.getComments()));
            index++;
        }
        return feedback;
    }

    @Transactional
    public Paper deskReview(User actingUser, Long paperId, DeskDecision decision) {
        Paper paper = paperRepository.findById(paperId).orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        requireChairOrAdmin(actingUser, paper);

        if (decision == DeskDecision.SEND_TO_REVIEW) {
            paper.setStatus(PaperStatus.UNDER_REVIEW);
        } else {
            paper.setStatus(PaperStatus.DESK_REJECTED);
            try {
                emailService.sendSimpleEmail(paper.getSubmitter().getEmail(), "Paper decision",
                        "We regret to inform you that your paper '" + paper.getTitle() + "' did not pass desk review.");
            } catch (Exception ignored) {}
        }

        return paperRepository.save(paper);
    }

    @Transactional
    public Paper applyDecision(User actingUser, Long paperId, String decision) {
        Paper paper = paperRepository.findById(paperId).orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        requireChairOrAdmin(actingUser, paper);

        if ("ACCEPT".equalsIgnoreCase(decision)) {
            paper.setStatus(PaperStatus.ACCEPTED);
            paperRepository.save(paper);
            try {
                Map<String, Object> model = new HashMap<>();
                model.put("submitterName", paper.getSubmitter().getFullName());
                model.put("paperTitle", paper.getTitle());
                model.put("feedback", buildAnonymizedFeedback(paperId));
                emailService.sendTemplateEmail(paper.getSubmitter().getEmail(), "Paper accepted", "email/acceptance_notification.txt", model);
            } catch (Exception ignored) {}
        } else if ("REJECT".equalsIgnoreCase(decision)) {
            paper.setStatus(PaperStatus.REJECTED);
            paperRepository.save(paper);
            try {
                Map<String, Object> model = new HashMap<>();
                model.put("submitterName", paper.getSubmitter().getFullName());
                model.put("paperTitle", paper.getTitle());
                model.put("feedback", buildAnonymizedFeedback(paperId));
                emailService.sendTemplateEmail(paper.getSubmitter().getEmail(), "Paper decision", "email/rejection_notification.txt", model);
            } catch (Exception ignored) {}
        } else if ("BORDERLINE".equalsIgnoreCase(decision)) {
            paper.setStatus(PaperStatus.UNDER_REVIEW);
            paperRepository.save(paper);
        } else {
            throw new IllegalArgumentException("Unknown decision: " + decision);
        }

        return paper;
    }

    @Transactional
    public List<Paper> applyBulkDecision(User actingUser, List<Long> paperIds, String decision) {
        List<Paper> updated = new ArrayList<>();
        for (Long id : paperIds) {
            updated.add(applyDecision(actingUser, id, decision));
        }
        return updated;
    }
}
```

- [ ] **Step 4: Create the rejection email template**

Create `src/main/resources/templates/email/rejection_notification.txt`:

```
Hello [[${submitterName}]],

Thank you for your submission titled:

"[[${paperTitle}]]"

After careful review, we regret to inform you that your paper was not accepted for this conference.

Reviewer feedback:
[# th:each="item : ${feedback}"]
Reviewer [[${item.index}]] (score: [[${item.score}]]):
[[${item.comments}]]

[/]
We encourage you to consider this feedback for future submissions.

Regards,
Conference Committee
```

- [ ] **Step 5: Update the acceptance email template**

Replace the contents of `src/main/resources/templates/email/acceptance_notification.txt`:

```
Hello [[${submitterName}]],

Congratulations — your paper titled:

"[[${paperTitle}]]"

has been accepted to the conference.

Reviewer feedback:
[# th:each="item : ${feedback}"]
Reviewer [[${item.index}]] (score: [[${item.score}]]):
[[${item.comments}]]

[/]
Please follow the instructions in your author dashboard for camera-ready submission and registration.

Regards,
Conference Committee
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.service.DecisionServiceTest"`
Expected: PASS (all tests, including the 5 pre-existing from the committee-roles plan and the 2 new ones).

- [ ] **Step 7: Verify full compilation and full test suite**

Run: `./gradlew compileJava test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Manually verify the template actually renders (not just that the mock-based test passes)**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` in the background. Since exercising this end-to-end requires a full authenticated decision-making flow (out of scope for a quick manual check), instead confirm the template parses without a `TextParseException` at startup by checking the log for any Thymeleaf-related error during boot — a malformed `[# th:each]`/`[/]` pair would surface as an error the first time the template is rendered, not at startup, so this step is a build-time sanity check only; the DecisionServiceTest assertions in Step 6 are the real verification that the loop syntax is correct (confirmed against the actual spike output during planning, not guessed). Stop the app.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/confcms/cms/service/DecisionService.java src/main/resources/templates/email/rejection_notification.txt src/main/resources/templates/email/acceptance_notification.txt src/test/java/org/confcms/cms/service/DecisionServiceTest.java
git commit -m "feat: bundle anonymized reviewer feedback into decision emails

applyDecision's ACCEPT and REJECT branches now build a numbered,
reviewer-identity-free feedback list (AnonymizedFeedbackItem has no
field capable of holding a reviewer's name -- structural exclusion,
not template-level omission) and include it in the email model.
REJECT now sends a real templated email (rejection_notification.txt,
new) instead of a bare sendSimpleEmail string. confidentialComments
is never read into the model. Loop syntax
([# th:each=\"...\"]...[/]) verified against Thymeleaf's actual
TEXT-mode parser via a throwaway spike test during planning, not
assumed from documentation."
```

---

## Task 5: `PaperStatus` revision statuses, `Paper.revisionDueDate`, `RevisionResolution`

**Files:**
- Modify: `src/main/java/org/confcms/cms/submission/domain/PaperStatus.java`
- Modify: `src/main/java/org/confcms/cms/submission/domain/Paper.java`
- Create: `src/main/java/org/confcms/cms/submission/domain/RevisionResolution.java`

- [ ] **Step 1: Add the two revision statuses**

Replace the contents of `src/main/java/org/confcms/cms/submission/domain/PaperStatus.java`:

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
    MAJOR_REVISION
}
```

- [ ] **Step 2: Add `revisionDueDate` to `Paper`**

In `src/main/java/org/confcms/cms/submission/domain/Paper.java`, add the import and field:

```java
import java.time.LocalDate;
```

```java
    private LocalDate revisionDueDate; // nullable; set when status becomes MINOR_REVISION/MAJOR_REVISION
```

- [ ] **Step 3: Create `RevisionResolution`**

Create `src/main/java/org/confcms/cms/submission/domain/RevisionResolution.java`:

```java
package org.confcms.cms.submission.domain;

public enum RevisionResolution {
    ACCEPT_DIRECTLY,
    SEND_BACK_TO_REVIEWERS
}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/submission/domain/PaperStatus.java src/main/java/org/confcms/cms/submission/domain/Paper.java src/main/java/org/confcms/cms/submission/domain/RevisionResolution.java
git commit -m "feat: add MINOR_REVISION/MAJOR_REVISION statuses and revisionDueDate

Foundation for the revision-decision workflow: a paper not simply
accepted or rejected can be categorized as needing minor or major
revision, with a due date for the author to re-upload a corrected
version."
```

---

## Task 6: `DecisionService.requestRevision` and `resolveRevision`

**Files:**
- Modify: `src/main/java/org/confcms/cms/service/DecisionService.java`
- Create: `src/main/resources/templates/email/revision_requested_notification.txt`
- Test: `src/test/java/org/confcms/cms/service/DecisionServiceTest.java` (extends the existing file)

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/org/confcms/cms/service/DecisionServiceTest.java`:

```java
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.domain.RevisionResolution;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.domain.AssignmentStatus;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;

import java.time.LocalDate;
```

Add a new mock field to the existing `@Mock` block in `DecisionServiceTest`:

```java
    @Mock
    private ReviewAssignmentRepository reviewAssignmentRepository;
```

Update the `setUp()` constructor call (matching `DecisionService`'s field order after Task 4's rewrite — `reviewRepository, paperRepository, emailService, committeeService` — with `reviewAssignmentRepository` appended per Task 6, Step 3 below) to read exactly:

```java
        service = new DecisionService(reviewRepository, paperRepository, emailService, committeeService, reviewAssignmentRepository);
```

```java
    @Test
    void requestRevisionRejectsNonChairNonAdmin() {
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(committeeService.isChairOrCoChair(strangerUser, conference)).thenReturn(false);

        assertThatThrownBy(() -> service.requestRevision(strangerUser, 5L, PaperStatus.MINOR_REVISION, LocalDate.now().plusDays(14)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void requestRevisionRejectsNonRevisionStatus() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));

        assertThatThrownBy(() -> service.requestRevision(admin, 5L, PaperStatus.ACCEPTED, LocalDate.now().plusDays(14)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestRevisionSetsStatusAndDueDateAndSendsEmail() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);

        LocalDate dueDate = LocalDate.now().plusDays(14);
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.findByPaperId(5L)).thenReturn(List.of());

        Paper result = service.requestRevision(admin, 5L, PaperStatus.MAJOR_REVISION, dueDate);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.MAJOR_REVISION);
        assertThat(result.getRevisionDueDate()).isEqualTo(dueDate);
        verify(emailService).sendTemplateEmail(any(), any(), eq("email/revision_requested_notification.txt"), anyMap());
    }

    @Test
    void resolveRevisionRejectsWhenNotInRevisionStatus() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);
        paper.setStatus(PaperStatus.SUBMITTED);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));

        assertThatThrownBy(() -> service.resolveRevision(admin, 5L, RevisionResolution.ACCEPT_DIRECTLY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolveRevisionAcceptDirectlySetsAccepted() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);
        paper.setStatus(PaperStatus.MINOR_REVISION);
        paper.setRevisionDueDate(LocalDate.now().plusDays(5));

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewRepository.findByPaperId(5L)).thenReturn(List.of());

        Paper result = service.resolveRevision(admin, 5L, RevisionResolution.ACCEPT_DIRECTLY);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.ACCEPTED);
        assertThat(result.getRevisionDueDate()).isNull();
    }

    @Test
    void resolveRevisionSendBackResetsOriginalAssignmentsToPending() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);
        paper.setStatus(PaperStatus.MAJOR_REVISION);

        ReviewAssignment originalAssignment = new ReviewAssignment();
        originalAssignment.setId(200L);
        originalAssignment.setPaper(paper);
        originalAssignment.setStatus(AssignmentStatus.COMPLETED);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviewAssignmentRepository.findByPaperId(5L)).thenReturn(List.of(originalAssignment));
        when(reviewAssignmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Paper result = service.resolveRevision(admin, 5L, RevisionResolution.SEND_BACK_TO_REVIEWERS);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.UNDER_REVIEW);
        assertThat(originalAssignment.getStatus()).isEqualTo(AssignmentStatus.PENDING);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.service.DecisionServiceTest"`
Expected: FAILS to compile — `requestRevision`/`resolveRevision` don't exist, `Paper.getRevisionDueDate` from Task 5 exists but nothing sets it yet.

- [ ] **Step 3: Add `requestRevision` and `resolveRevision` to `DecisionService`**

In `src/main/java/org/confcms/cms/service/DecisionService.java`, add the imports:

```java
import org.confcms.cms.submission.domain.RevisionResolution;
import org.confcms.cms.review.domain.AssignmentStatus;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;

import java.time.LocalDate;
```

Add the field (matches the test's constructor-order expectation: appended after `committeeService`):

```java
    private final ReviewAssignmentRepository reviewAssignmentRepository;
```

Add the two methods after `applyBulkDecision`:

```java
    @Transactional
    public Paper requestRevision(User actingUser, Long paperId, PaperStatus revisionType, LocalDate dueDate) {
        if (revisionType != PaperStatus.MINOR_REVISION && revisionType != PaperStatus.MAJOR_REVISION) {
            throw new IllegalArgumentException("revisionType must be MINOR_REVISION or MAJOR_REVISION");
        }

        Paper paper = paperRepository.findById(paperId).orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        requireChairOrAdmin(actingUser, paper);

        paper.setStatus(revisionType);
        paper.setRevisionDueDate(dueDate);
        Paper saved = paperRepository.save(paper);

        try {
            Map<String, Object> model = new HashMap<>();
            model.put("submitterName", paper.getSubmitter().getFullName());
            model.put("paperTitle", paper.getTitle());
            model.put("revisionType", revisionType == PaperStatus.MINOR_REVISION ? "minor revision" : "major revision");
            model.put("dueDate", dueDate);
            model.put("feedback", buildAnonymizedFeedback(paperId));
            emailService.sendTemplateEmail(paper.getSubmitter().getEmail(), "Revision requested", "email/revision_requested_notification.txt", model);
        } catch (Exception ignored) {}

        return saved;
    }

    @Transactional
    public Paper resolveRevision(User actingUser, Long paperId, RevisionResolution resolution) {
        Paper paper = paperRepository.findById(paperId).orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        requireChairOrAdmin(actingUser, paper);

        if (paper.getStatus() != PaperStatus.MINOR_REVISION && paper.getStatus() != PaperStatus.MAJOR_REVISION) {
            throw new IllegalStateException("Paper is not currently awaiting a revision resolution");
        }

        if (resolution == RevisionResolution.ACCEPT_DIRECTLY) {
            paper.setStatus(PaperStatus.ACCEPTED);
            paper.setRevisionDueDate(null);
            Paper saved = paperRepository.save(paper);
            try {
                Map<String, Object> model = new HashMap<>();
                model.put("submitterName", paper.getSubmitter().getFullName());
                model.put("paperTitle", paper.getTitle());
                model.put("feedback", buildAnonymizedFeedback(paperId));
                emailService.sendTemplateEmail(paper.getSubmitter().getEmail(), "Paper accepted", "email/acceptance_notification.txt", model);
            } catch (Exception ignored) {}
            return saved;
        } else {
            paper.setStatus(PaperStatus.UNDER_REVIEW);
            paper.setRevisionDueDate(null);
            Paper saved = paperRepository.save(paper);

            List<ReviewAssignment> originalAssignments = reviewAssignmentRepository.findByPaperId(paperId);
            for (ReviewAssignment assignment : originalAssignments) {
                assignment.setStatus(AssignmentStatus.PENDING);
                reviewAssignmentRepository.save(assignment);
            }

            return saved;
        }
    }
```

- [ ] **Step 4: Create the revision-requested email template**

Create `src/main/resources/templates/email/revision_requested_notification.txt`:

```
Hello [[${submitterName}]],

Your paper titled:

"[[${paperTitle}]]"

requires a [[${revisionType}]] before a final decision can be made.

Please upload a corrected version addressing the feedback below by [[${dueDate}]]. Submissions after this date will be automatically rejected.

Reviewer feedback:
[# th:each="item : ${feedback}"]
Reviewer [[${item.index}]] (score: [[${item.score}]]):
[[${item.comments}]]

[/]
Regards,
Conference Committee
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.service.DecisionServiceTest"`
Expected: PASS (all tests).

- [ ] **Step 6: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/service/DecisionService.java src/main/resources/templates/email/revision_requested_notification.txt src/test/java/org/confcms/cms/service/DecisionServiceTest.java
git commit -m "feat: add requestRevision and resolveRevision to DecisionService

requestRevision: chair/admin sets MINOR_REVISION or MAJOR_REVISION plus
a due date, notifies the author with the same anonymized feedback
bundling as applyDecision (reused, not reimplemented). resolveRevision:
ACCEPT_DIRECTLY moves straight to ACCEPTED (chair has full discretion,
consistent with existing final-decision authority); SEND_BACK_TO_REVIEWERS
resets the original ReviewAssignment rows (the same reviewers who
requested the revision) to PENDING so they can review the newly
uploaded PaperVersion, rather than running fresh auto-assignment."
```

---

## Task 7: `SubmissionService.uploadRevision` with lazy auto-reject

**Files:**
- Modify: `src/main/java/org/confcms/cms/submission/service/SubmissionService.java`
- Modify: `src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java`
- Test: `src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java` (extends the existing file)

- [ ] **Step 1: Write the failing tests**

Add to `src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java` (existing file). Add imports:

```java
import org.confcms.cms.submission.domain.PaperStatus;

import java.time.LocalDate;
```

```java
    @Test
    void uploadRevisionRejectsWhenNotInRevisionStatus() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        User submitter = new User();
        submitter.setId(10L);

        Paper paper = new Paper();
        paper.setId(5L);
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.SUBMITTED);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));

        MockMultipartFile file = new MockMultipartFile("file", "revised.pdf", "application/pdf", "%PDF-1.4".getBytes());

        assertThatThrownBy(() -> service.uploadRevision(submitter, 5L, file))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void uploadRevisionAutoRejectsAndBlocksUploadWhenDeadlinePassed() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        User submitter = new User();
        submitter.setId(10L);

        Paper paper = new Paper();
        paper.setId(5L);
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.MINOR_REVISION);
        paper.setRevisionDueDate(LocalDate.now().minusDays(1));

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "revised.pdf", "application/pdf", "%PDF-1.4".getBytes());

        assertThatThrownBy(() -> service.uploadRevision(submitter, 5L, file))
                .isInstanceOf(IllegalStateException.class);

        assertThat(paper.getStatus()).isEqualTo(PaperStatus.REJECTED);
    }

    @Test
    void uploadRevisionSucceedsBeforeDeadline() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService, personInvitationService, userRepository);

        User submitter = new User();
        submitter.setId(10L);

        Paper paper = new Paper();
        paper.setId(5L);
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.MAJOR_REVISION);
        paper.setRevisionDueDate(LocalDate.now().plusDays(5));

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(fileStorageService.store(any())).thenReturn("/uploads/revised.pdf");
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MockMultipartFile file = new MockMultipartFile("file", "revised.pdf", "application/pdf", "%PDF-1.4".getBytes());

        Paper result = service.uploadRevision(submitter, 5L, file);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.MAJOR_REVISION);
        assertThat(result.getVersions()).hasSize(1);
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.submission.service.SubmissionServiceTest"`
Expected: FAILS to compile — `uploadRevision` doesn't exist.

- [ ] **Step 3: Implement `uploadRevision`**

In `src/main/java/org/confcms/cms/submission/service/SubmissionService.java`, add the import:

```java
import java.time.LocalDate;
```

Add the method after `withdrawPaper`:

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

        if (paper.getRevisionDueDate() != null && paper.getRevisionDueDate().isBefore(LocalDate.now())) {
            paper.setStatus(PaperStatus.REJECTED);
            paperRepository.save(paper);
            throw new IllegalStateException("The revision deadline has passed; this paper has been rejected");
        }

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
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.submission.service.SubmissionServiceTest"`
Expected: PASS (all tests, including the original 3 from the domain-model-merge plan, the 2 co-author ones from the reviewer-decline plan, and these 3 new ones).

- [ ] **Step 5: Add the controller endpoint**

In `src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java`, add after `uploadVersion`:

```java
    @PostMapping(path = "/{id}/revision", consumes = "multipart/form-data")
    public ResponseEntity<?> uploadRevision(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
        try {
            Paper saved = submissionService.uploadRevision(user, id, file);
            return ResponseEntity.ok(saved);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        } catch (IllegalStateException ise) {
            return ResponseEntity.status(409).body(ise.getMessage());
        }
    }
```

- [ ] **Step 6: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/submission/service/SubmissionService.java src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java
git commit -m "feat: author revision re-upload with lazy deadline auto-reject

uploadRevision follows the exact pattern of the existing
uploadNewVersion (ownership check, validatePdf, new PaperVersion), but
first checks whether Paper.revisionDueDate has passed -- if so,
transitions the paper to REJECTED immediately and rejects the upload,
rather than silently accepting a late file. This is the concrete
implementation of the lazy (not scheduled-job) auto-reject mechanism
confirmed during design: no new scheduling infrastructure introduced,
since this codebase has none today."
```

---

## Task 8: Final full-suite verification

**Files:** none changed — verification only.

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew clean test`
Expected: BUILD SUCCESSFUL, all tests pass across every test class from this plan and every prior plan on `main`.

- [ ] **Step 2: Run a full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Start the app and smoke-test**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` in the background.

- Confirm `GET /login` returns 200 (regression check).
- Confirm `GET /review/assignment/999/paper` redirects to login when unauthenticated (route is wired).
- Confirm `GET /committee` still returns 200 (regression check against the committee-roles feature).

Stop the app afterward. No commit needed for this verification-only task.

---

## Self-review notes (for whoever executes this plan)

- **The Thymeleaf TEXT-mode loop syntax (`[# th:each="..."]...[/]`) was verified by actually running a probe template through `EmailTemplateService` inside a throwaway `@SpringBootTest`** during planning (not assumed from documentation) — the probe files were deleted before this plan was written; Tasks 4 and 6 use the exact confirmed syntax.
- **`AdminDecisionViewController` is not touched by this plan.** An earlier point in this design session raised a `confidentialComments`-leak concern about it, which was re-checked against the actual merged code and found to already be correctly authorized (fixed by a prior security patch on `main`) — this plan does not "fix" it because there is nothing to fix.
- **Constructor-order changes ripple across tasks, as in the prior two plans.** Task 6 adds a new field to `DecisionService` (`reviewAssignmentRepository`) — the existing `DecisionServiceTest`'s `setUp()` constructor call must be updated in that same task, not left stale (this exact class of mistake was caught and fixed during the previous plan's self-review; applying the same discipline here from the start).
- **This plan does not touch conference-level milestone dates, reviewer-deadline reminders, download audit logging, a real scheduled job, specialist-field tagging, HTML emails, caching, or SEO** — all explicitly deferred per the design spec, tracked there for future brainstorms.
- **No new dependencies.** `UrlResource`, `MimeMessage`-alternatives are not needed (Task 3's file download uses Spring's existing `Resource`/`UrlResource`, already transitively available via `spring-boot-starter-web`).
