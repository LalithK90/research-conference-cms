# Committee Roles (Chair/Co-Chair) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add conference-scoped committee roles (Chair, Co-Chair, Reviewer, Finance Manager, Registration Manager, Proceedings Manager), give Chair/Co-Chair desk-review and reviewer-assignment authority (with ADMIN override), require a Chair at conference creation, and replace the disconnected `SteeringCommitteeMember` with the new role data on the public committee page.

**Architecture:** New `ConferenceCommitteeRole` join entity (Conference × User × CommitteeRole) with a `CommitteeService` for permission checks. Existing `DecisionService` and `ReviewAssignmentService` gain an acting-user parameter and a per-conference authorization check. A **prerequisite fix** (not in the original design spec, discovered while planning): `Paper` currently has no link to `Conference` at all, which every authorization check in this plan depends on — Task 1 adds it before anything else.

**Tech Stack:** Spring Boot 3.5, Java 21, JPA/Hibernate, Spring Security, Thymeleaf, JUnit 5 + AssertJ (already on the test classpath via `spring-boot-starter-test`), Bean Validation (`spring-boot-starter-validation`, already a dependency).

---

## Context for the engineer picking this up

This plan implements `docs/superpowers/specs/2026-09-19-committee-roles-design.md`. Read that spec first for the full rationale (role set validated against EasyChair's published role model; scope decisions on what's deferred).

**One material gap was found between the spec and the current codebase, corrected here:** the spec's authorization design assumes every `Paper` has a `getConference()` accessor. It doesn't — `Paper.track` is a plain `String`, not a link to the conference-scoped `SubTheme` entity, and no other path from `Paper` to `Conference` exists anywhere in the schema. Task 1 adds `Paper.conference`, set from the active conference at submission time (`SubmissionService.submitPaper` is the sole creation point for `Paper`, confirmed by an exhaustive `grep` — nothing else calls `new Paper()`). Every later task's authorization checks depend on Task 1 landing first.

The codebase currently has one canonical domain-model layout: `Conference`, `User`, `SubTheme`, etc. live in `org.confcms.cms.domain`; `ConferenceService`/`DecisionService`/`EmailService` live in `org.confcms.cms.service`; `Paper`/`PaperStatus`/`PaperVersion` live in `org.confcms.cms.submission.domain`; `ReviewAssignmentService` lives in `org.confcms.cms.review.service`. This plan follows that existing layout — new files land in `org.confcms.cms.domain`, `.repository`, and `.service` to match `Conference`/`ConferenceService`'s existing location, not a new `committee.*` feature package.

---

## Task 1: Add `Paper.conference`, set at submission time

**Files:**
- Modify: `src/main/java/org/confcms/cms/submission/domain/Paper.java`
- Modify: `src/main/java/org/confcms/cms/submission/service/SubmissionService.java`
- Test: `src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java`:

```java
package org.confcms.cms.submission.service;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.service.ConferenceService;
import org.confcms.cms.service.EmailService;
import org.confcms.cms.service.FileStorageService;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.repository.PaperRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDate;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceTest {

    @Mock
    private PaperRepository paperRepository;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private EmailService emailService;
    @Mock
    private ConferenceService conferenceService;

    @Test
    void submitPaperSetsConferenceFromActiveConference() {
        SubmissionService service = new SubmissionService(paperRepository, fileStorageService, emailService, conferenceService);

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

        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", "%PDF-1.4".getBytes());

        Paper saved = service.submitPaper(submitter, "Title", "Abstract", "Track A", file, Collections.emptyList());

        assertThat(saved.getConference()).isEqualTo(activeConference);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.submission.service.SubmissionServiceTest"`
Expected: FAILS to compile — `SubmissionService(PaperRepository, FileStorageService, EmailService, ConferenceService)` constructor doesn't exist yet (current constructor only takes 3 args via `@RequiredArgsConstructor`), and `Paper.getConference()` doesn't exist.

- [ ] **Step 3: Add `conference` field to `Paper`**

In `src/main/java/org/confcms/cms/submission/domain/Paper.java`, add the import and field:

```java
package org.confcms.cms.submission.domain;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.core.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "papers")
@Getter
@Setter
public class Paper extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String abstractText;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitter_id", nullable = false)
    private User submitter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaperStatus status = PaperStatus.SUBMITTED;

    @Column(nullable = false)
    private String track; // Theme/Track

    @OneToMany(mappedBy = "paper", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PaperVersion> versions = new ArrayList<>();

    @OneToMany(mappedBy = "paper", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PaperAuthor> authors = new ArrayList<>();
}
```

- [ ] **Step 4: Wire `ConferenceService` into `SubmissionService` and set the conference on submission**

In `src/main/java/org/confcms/cms/submission/service/SubmissionService.java`, add the import and field (via the existing `@RequiredArgsConstructor` pattern — just add the field, Lombok generates the constructor):

```java
import org.confcms.cms.domain.User;
import org.confcms.cms.service.ConferenceService;
import org.confcms.cms.service.FileStorageService;
import org.confcms.cms.service.EmailService;
import org.confcms.cms.submission.domain.*;
import org.confcms.cms.submission.repository.PaperRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final PaperRepository paperRepository;
    private final FileStorageService fileStorageService;
    private final EmailService emailService;
    private final ConferenceService conferenceService;

    @Transactional
    public Paper submitPaper(User submitter, String title, String abstractText, String track, MultipartFile file, List<PaperAuthor> authors) {
        Paper paper = new Paper();
        paper.setConference(conferenceService.getActiveConference());
        paper.setSubmitter(submitter);
        paper.setTitle(title);
        paper.setAbstractText(abstractText);
        paper.setTrack(track);
        paper.setStatus(PaperStatus.SUBMITTED);
```

Leave the rest of `submitPaper` and every other method in the file unchanged — only the two lines above change (the new import/field and the one `paper.setConference(...)` call).

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.submission.service.SubmissionServiceTest"`
Expected: PASS

- [ ] **Step 6: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL. (Existing calls to `new SubmissionService(...)` don't exist outside Spring's own dependency injection — `@RequiredArgsConstructor` means Spring wires the new `ConferenceService` param automatically; no other file needs updating.)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/submission/domain/Paper.java src/main/java/org/confcms/cms/submission/service/SubmissionService.java src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java
git commit -m "feat: link Paper to its Conference, set from the active conference at submission

Every authorization check the upcoming committee-roles feature needs
(is this user Chair of the conference this paper belongs to) requires
Paper -> Conference. No such link existed anywhere in the schema --
Paper.track was a plain String, not tied to the conference-scoped
SubTheme entity. Adds the field and sets it from
ConferenceService.getActiveConference() at submission time, the sole
place a Paper is created."
```

---

## Task 2: `CommitteeRole` enum and `ConferenceCommitteeRole` entity

**Files:**
- Create: `src/main/java/org/confcms/cms/domain/CommitteeRole.java`
- Create: `src/main/java/org/confcms/cms/domain/ConferenceCommitteeRole.java`
- Modify: `src/main/java/org/confcms/cms/domain/Conference.java`

- [ ] **Step 1: Create the `CommitteeRole` enum**

Create `src/main/java/org/confcms/cms/domain/CommitteeRole.java`:

```java
package org.confcms.cms.domain;

public enum CommitteeRole {
    CHAIR,
    CO_CHAIR,
    REVIEWER,
    FINANCE_MANAGER,
    REGISTRATION_MANAGER,
    PROCEEDINGS_MANAGER
}
```

- [ ] **Step 2: Create the `ConferenceCommitteeRole` entity**

Create `src/main/java/org/confcms/cms/domain/ConferenceCommitteeRole.java`:

```java
package org.confcms.cms.domain;

import org.confcms.cms.core.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "conference_committee_roles",
       uniqueConstraints = @UniqueConstraint(columnNames = {"conference_id", "user_id", "role"}))
@Getter
@Setter
public class ConferenceCommitteeRole extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommitteeRole role;

    private String displayTitle; // optional public-page label; null -> render role.name()

    @Column(columnDefinition = "TEXT")
    private String bio;

    private String photoUrl;
}
```

- [ ] **Step 3: Replace `Conference.steeringCommittee` with `Conference.committeeRoles`**

In `src/main/java/org/confcms/cms/domain/Conference.java`, replace:

```java
    @OneToMany(mappedBy = "conference", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SteeringCommitteeMember> steeringCommittee = new ArrayList<>();
```

with:

```java
    @OneToMany(mappedBy = "conference", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ConferenceCommitteeRole> committeeRoles = new ArrayList<>();
```

- [ ] **Step 4: Verify compilation fails only on the expected file**

Run: `./gradlew compileJava`
Expected: FAILS — `SteeringCommitteeMember` still exists as its own file and still compiles fine on its own, but nothing yet uses the new `committeeRoles` field, so this step should actually succeed. Run it to confirm: BUILD SUCCESSFUL. (`SteeringCommitteeMember.java` itself still compiles standalone since it's not deleted until Task 8 — this task only stops `Conference` from referencing it.)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/domain/CommitteeRole.java src/main/java/org/confcms/cms/domain/ConferenceCommitteeRole.java src/main/java/org/confcms/cms/domain/Conference.java
git commit -m "feat: add CommitteeRole enum and ConferenceCommitteeRole entity

Six roles (CHAIR, CO_CHAIR, REVIEWER, FINANCE_MANAGER,
REGISTRATION_MANAGER, PROCEEDINGS_MANAGER), validated against
EasyChair's published role model per the design spec. Conference now
points at committeeRoles instead of the old free-text
SteeringCommitteeMember (deleted in a later task, once nothing
references it)."
```

---

## Task 3: `ConferenceCommitteeRoleRepository` and `CommitteeService`

**Files:**
- Create: `src/main/java/org/confcms/cms/repository/ConferenceCommitteeRoleRepository.java`
- Create: `src/main/java/org/confcms/cms/service/CommitteeService.java`
- Test: `src/test/java/org/confcms/cms/service/CommitteeServiceTest.java`

- [ ] **Step 1: Create the repository**

Create `src/main/java/org/confcms/cms/repository/ConferenceCommitteeRoleRepository.java`:

```java
package org.confcms.cms.repository;

import org.confcms.cms.domain.CommitteeRole;
import org.confcms.cms.domain.ConferenceCommitteeRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConferenceCommitteeRoleRepository extends JpaRepository<ConferenceCommitteeRole, Long> {
    List<ConferenceCommitteeRole> findByConferenceId(Long conferenceId);
    Optional<ConferenceCommitteeRole> findByConferenceIdAndUserIdAndRole(Long conferenceId, Long userId, CommitteeRole role);
    Optional<ConferenceCommitteeRole> findByConferenceIdAndRole(Long conferenceId, CommitteeRole role);
}
```

(`findByConferenceIdAndRole` assumes at most one `CHAIR` row per conference — enforced by `CommitteeService.assignChair` always deleting the prior one first, so this is safe to treat as a single-result lookup for the Chair specifically.)

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/org/confcms/cms/service/CommitteeServiceTest.java`:

```java
package org.confcms.cms.service;

import org.confcms.cms.domain.CommitteeRole;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.ConferenceCommitteeRole;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.ConferenceCommitteeRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommitteeServiceTest {

    @Mock
    private ConferenceCommitteeRoleRepository repository;

    private CommitteeService service;
    private Conference conference;
    private User user;

    @BeforeEach
    void setUp() {
        service = new CommitteeService(repository);
        conference = new Conference();
        conference.setId(1L);
        user = new User();
        user.setId(10L);
    }

    @Test
    void isChairOrCoChairTrueForChair() {
        ConferenceCommitteeRole chairRole = new ConferenceCommitteeRole();
        chairRole.setRole(CommitteeRole.CHAIR);
        when(repository.findByConferenceIdAndUserIdAndRole(1L, 10L, CommitteeRole.CHAIR))
                .thenReturn(Optional.of(chairRole));

        assertThat(service.isChairOrCoChair(user, conference)).isTrue();
    }

    @Test
    void isChairOrCoChairFalseForReviewerOnly() {
        when(repository.findByConferenceIdAndUserIdAndRole(1L, 10L, CommitteeRole.CHAIR))
                .thenReturn(Optional.empty());
        when(repository.findByConferenceIdAndUserIdAndRole(1L, 10L, CommitteeRole.CO_CHAIR))
                .thenReturn(Optional.empty());

        assertThat(service.isChairOrCoChair(user, conference)).isFalse();
    }

    @Test
    void assignChairDemotesPriorChair() {
        ConferenceCommitteeRole priorChair = new ConferenceCommitteeRole();
        priorChair.setId(99L);
        priorChair.setRole(CommitteeRole.CHAIR);
        when(repository.findByConferenceIdAndRole(1L, CommitteeRole.CHAIR))
                .thenReturn(Optional.of(priorChair));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.assignChair(conference, user);

        verify(repository).delete(priorChair);
        verify(repository).save(argThat(role -> role.getRole() == CommitteeRole.CHAIR && role.getUser() == user));
    }

    @Test
    void addRoleRejectsDuplicate() {
        ConferenceCommitteeRole existing = new ConferenceCommitteeRole();
        when(repository.findByConferenceIdAndUserIdAndRole(1L, 10L, CommitteeRole.REVIEWER))
                .thenReturn(Optional.of(existing));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.addRole(conference, user, CommitteeRole.REVIEWER, null));
    }

    @Test
    void getCommitteeForConferenceDelegatesToRepository() {
        List<ConferenceCommitteeRole> roles = List.of(new ConferenceCommitteeRole());
        when(repository.findByConferenceId(1L)).thenReturn(roles);

        assertThat(service.getCommitteeForConference(conference)).isEqualTo(roles);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.service.CommitteeServiceTest"`
Expected: FAILS to compile — `CommitteeService` doesn't exist yet.

- [ ] **Step 4: Create `CommitteeService`**

Create `src/main/java/org/confcms/cms/service/CommitteeService.java`:

```java
package org.confcms.cms.service;

import org.confcms.cms.domain.CommitteeRole;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.ConferenceCommitteeRole;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.ConferenceCommitteeRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CommitteeService {

    private final ConferenceCommitteeRoleRepository repository;

    public boolean hasRole(User user, Conference conference, CommitteeRole role) {
        return repository.findByConferenceIdAndUserIdAndRole(conference.getId(), user.getId(), role).isPresent();
    }

    public boolean isChairOrCoChair(User user, Conference conference) {
        return hasRole(user, conference, CommitteeRole.CHAIR) || hasRole(user, conference, CommitteeRole.CO_CHAIR);
    }

    public List<ConferenceCommitteeRole> getCommitteeForConference(Conference conference) {
        return repository.findByConferenceId(conference.getId());
    }

    @Transactional
    public ConferenceCommitteeRole assignChair(Conference conference, User user) {
        repository.findByConferenceIdAndRole(conference.getId(), CommitteeRole.CHAIR)
                .ifPresent(repository::delete);

        ConferenceCommitteeRole chairRole = new ConferenceCommitteeRole();
        chairRole.setConference(conference);
        chairRole.setUser(user);
        chairRole.setRole(CommitteeRole.CHAIR);
        return repository.save(chairRole);
    }

    @Transactional
    public ConferenceCommitteeRole addRole(Conference conference, User user, CommitteeRole role, String displayTitle) {
        repository.findByConferenceIdAndUserIdAndRole(conference.getId(), user.getId(), role)
                .ifPresent(existing -> {
                    throw new IllegalStateException("This person already holds that role on this conference");
                });

        ConferenceCommitteeRole committeeRole = new ConferenceCommitteeRole();
        committeeRole.setConference(conference);
        committeeRole.setUser(user);
        committeeRole.setRole(role);
        committeeRole.setDisplayTitle(displayTitle);
        return repository.save(committeeRole);
    }

    @Transactional
    public void removeRole(Long conferenceCommitteeRoleId) {
        repository.deleteById(conferenceCommitteeRoleId);
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.service.CommitteeServiceTest"`
Expected: PASS (5 tests)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/confcms/cms/repository/ConferenceCommitteeRoleRepository.java src/main/java/org/confcms/cms/service/CommitteeService.java src/test/java/org/confcms/cms/service/CommitteeServiceTest.java
git commit -m "feat: add CommitteeService for per-conference role checks

isChairOrCoChair is the primary check the desk-review and
reviewer-assignment authorization (next tasks) will call. assignChair
auto-demotes any prior Chair in the same transaction, matching the
existing 'only one active conference' pattern in
ConferenceService.saveConference. addRole rejects a duplicate
(conference, user, role) with a clean IllegalStateException rather
than letting a raw DB constraint violation surface."
```

---

## Task 4: `DecisionService` — acting-user authorization + desk review

**Files:**
- Modify: `src/main/java/org/confcms/cms/service/DecisionService.java`
- Modify: `src/main/java/org/confcms/cms/submission/domain/PaperStatus.java`
- Modify: `src/main/java/org/confcms/cms/web/controller/AdminDecisionController.java`
- Modify: `src/main/java/org/confcms/cms/web/controller/AdminDecisionViewController.java`
- Test: `src/test/java/org/confcms/cms/service/DecisionServiceTest.java`

- [ ] **Step 1: Add `DESK_REJECTED` to `PaperStatus`**

Replace the contents of `src/main/java/org/confcms/cms/submission/domain/PaperStatus.java`:

```java
package org.confcms.cms.submission.domain;

public enum PaperStatus {
    SUBMITTED,
    UNDER_REVIEW,
    ACCEPTED,
    REJECTED,
    DESK_REJECTED,
    WITHDRAWN
}
```

- [ ] **Step 2: Write the failing tests**

Create `src/test/java/org/confcms/cms/service/DecisionServiceTest.java`:

```java
package org.confcms.cms.service;

import org.confcms.cms.domain.CommitteeRole;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.review.repository.ReviewRepository;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.repository.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DecisionServiceTest {

    @Mock
    private ReviewRepository reviewRepository;
    @Mock
    private PaperRepository paperRepository;
    @Mock
    private EmailService emailService;
    @Mock
    private CommitteeService committeeService;

    private DecisionService service;
    private Conference conference;
    private Paper paper;
    private User chairUser;
    private User strangerUser;

    @BeforeEach
    void setUp() {
        service = new DecisionService(reviewRepository, paperRepository, emailService, committeeService);

        conference = new Conference();
        conference.setId(1L);

        User submitter = new User();
        submitter.setFullName("Author Name");
        submitter.setEmail("author@example.com");

        paper = new Paper();
        paper.setId(5L);
        paper.setTitle("A Paper");
        paper.setConference(conference);
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.SUBMITTED);

        chairUser = new User();
        chairUser.setId(20L);
        chairUser.setRole(Role.REVIEWER); // not global ADMIN -- authorized only via committee role

        strangerUser = new User();
        strangerUser.setId(30L);
        strangerUser.setRole(Role.REVIEWER);
    }

    @Test
    void deskReviewRejectsNonChairNonAdmin() {
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(committeeService.isChairOrCoChair(strangerUser, conference)).thenReturn(false);

        assertThatThrownBy(() -> service.deskReview(strangerUser, 5L, DecisionService.DeskDecision.SEND_TO_REVIEW))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void deskReviewSendToReviewTransitionsToUnderReview() {
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(committeeService.isChairOrCoChair(chairUser, conference)).thenReturn(true);
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Paper result = service.deskReview(chairUser, 5L, DecisionService.DeskDecision.SEND_TO_REVIEW);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.UNDER_REVIEW);
    }

    @Test
    void deskReviewDeskRejectTransitionsToDeskRejectedAndEmails() {
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(committeeService.isChairOrCoChair(chairUser, conference)).thenReturn(true);
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Paper result = service.deskReview(chairUser, 5L, DecisionService.DeskDecision.DESK_REJECT);

        assertThat(result.getStatus()).isEqualTo(PaperStatus.DESK_REJECTED);
    }

    @Test
    void applyDecisionRejectsNonChairNonAdmin() {
        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(committeeService.isChairOrCoChair(strangerUser, conference)).thenReturn(false);

        assertThatThrownBy(() -> service.applyDecision(strangerUser, 5L, "ACCEPT"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void applyDecisionAllowsAdminEvenWithoutCommitteeRole() {
        User admin = new User();
        admin.setId(40L);
        admin.setRole(Role.ADMIN);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(paperRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Paper result = service.applyDecision(admin, 5L, "ACCEPT");

        assertThat(result.getStatus()).isEqualTo(PaperStatus.ACCEPTED);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.service.DecisionServiceTest"`
Expected: FAILS to compile — `DecisionService`'s constructor doesn't take a `CommitteeService`, `applyDecision`/`deskReview` don't take a `User` parameter, `DeskDecision` doesn't exist.

- [ ] **Step 4: Update `DecisionService`**

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
            // send acceptance email (templated)
            try {
                java.util.Map<String, Object> model = new java.util.HashMap<>();
                model.put("submitterName", paper.getSubmitter().getFullName());
                model.put("paperTitle", paper.getTitle());
                emailService.sendTemplateEmail(paper.getSubmitter().getEmail(), "Paper accepted", "email/acceptance_notification.txt", model);
            } catch (Exception ignored) {}
        } else if ("REJECT".equalsIgnoreCase(decision)) {
            paper.setStatus(PaperStatus.REJECTED);
            paperRepository.save(paper);
            try {
                emailService.sendSimpleEmail(paper.getSubmitter().getEmail(), "Paper decision",
                        "We regret to inform you that your paper '" + paper.getTitle() + "' was not accepted.");
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

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.service.DecisionServiceTest"`
Expected: PASS (5 tests)

- [ ] **Step 6: Update `AdminDecisionController` to resolve and pass the acting user**

Replace the contents of `src/main/java/org/confcms/cms/web/controller/AdminDecisionController.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.DecisionService;
import org.confcms.cms.submission.domain.Paper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/decisions")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
public class AdminDecisionController {

    private final DecisionService decisionService;
    private final UserRepository userRepository;

    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }

    @GetMapping("/suggestions")
    public ResponseEntity<?> suggestions(@RequestParam(defaultValue = "3.5") double acceptThreshold,
                                         @RequestParam(defaultValue = "2.5") double rejectThreshold) {
        return ResponseEntity.ok(decisionService.suggestDecisions(acceptThreshold, rejectThreshold));
    }

    @PostMapping("/{paperId}/apply")
    public ResponseEntity<?> applyDecision(@PathVariable Long paperId, @RequestParam String decision) {
        try {
            Paper p = decisionService.applyDecision(actingUser(), paperId, decision);
            return ResponseEntity.ok(p);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @PostMapping("/apply/bulk")
    public ResponseEntity<?> bulkApply(@RequestBody List<Long> paperIds, @RequestParam String decision) {
        try {
            List<Paper> updated = decisionService.applyBulkDecision(actingUser(), paperIds, decision);
            return ResponseEntity.ok(updated);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @PostMapping("/{paperId}/desk-review")
    public ResponseEntity<?> deskReview(@PathVariable Long paperId, @RequestParam DecisionService.DeskDecision decision) {
        try {
            Paper p = decisionService.deskReview(actingUser(), paperId, decision);
            return ResponseEntity.ok(p);
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }
}
```

Note the class-level `@PreAuthorize` relaxes from `hasRole('ADMIN')` to `hasAnyRole('ADMIN','REVIEWER')` — this is the coarse first-pass gate from the design spec; the real per-conference check happens inside `DecisionService` via `requireChairOrAdmin`. A plain `AUTHOR` still cannot reach these endpoints at all.

- [ ] **Step 7: Update `AdminDecisionViewController`**

In `src/main/java/org/confcms/cms/web/controller/AdminDecisionViewController.java`, change the class-level `@PreAuthorize` the same way:

```java
@PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
```

(This controller only reads data via `suggestDecisions`/`paperRepository.findById`/`reviewRepository.findByPaperId` — none of which take an acting-user parameter in this plan, so no further changes are needed here beyond the relaxed annotation.)

- [ ] **Step 8: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/confcms/cms/service/DecisionService.java src/main/java/org/confcms/cms/submission/domain/PaperStatus.java src/main/java/org/confcms/cms/web/controller/AdminDecisionController.java src/main/java/org/confcms/cms/web/controller/AdminDecisionViewController.java src/test/java/org/confcms/cms/service/DecisionServiceTest.java
git commit -m "feat: desk review + Chair/Co-Chair authorization on DecisionService

Adds DESK_REJECTED to PaperStatus and a new deskReview(actingUser,
paperId, decision) method, kept separate from applyDecision's untyped
String decision parameter since desk review and final decision are
different actions with different valid states and different email
copy. applyDecision/applyBulkDecision now require an acting User and
reject with SecurityException unless the caller is global ADMIN or
Chair/Co-Chair of the paper's conference. AdminDecisionController's
@PreAuthorize relaxes to hasAnyRole('ADMIN','REVIEWER') as the coarse
gate; the real per-conference decision happens inside the service."
```

---

## Task 5: `ReviewAssignmentService` — acting-user authorization

**Files:**
- Modify: `src/main/java/org/confcms/cms/review/service/ReviewAssignmentService.java`
- Modify: `src/main/java/org/confcms/cms/web/controller/ReviewRestController.java`
- Test: `src/test/java/org/confcms/cms/review/service/ReviewAssignmentServiceTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/confcms/cms/review/service/ReviewAssignmentServiceTest.java`:

```java
package org.confcms.cms.review.service;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;
import org.confcms.cms.review.repository.ReviewBidRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.repository.PaperRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewAssignmentServiceTest {

    @Mock
    private ReviewAssignmentRepository assignmentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PaperRepository paperRepository;
    @Mock
    private ReviewBidRepository bidRepository;
    @Mock
    private CommitteeService committeeService;

    private ReviewAssignmentService service;
    private Conference conference;
    private Paper paper;

    @BeforeEach
    void setUp() {
        service = new ReviewAssignmentService(assignmentRepository, userRepository, paperRepository, bidRepository, committeeService);

        conference = new Conference();
        conference.setId(1L);

        paper = new Paper();
        paper.setId(5L);
        paper.setConference(conference);
        paper.setStatus(PaperStatus.SUBMITTED);
    }

    @Test
    void autoAssignReviewersRejectsNonChairNonAdmin() {
        User stranger = new User();
        stranger.setId(30L);
        stranger.setRole(Role.REVIEWER);

        when(paperRepository.findById(5L)).thenReturn(Optional.of(paper));
        when(committeeService.isChairOrCoChair(stranger, conference)).thenReturn(false);

        assertThatThrownBy(() -> service.autoAssignReviewers(stranger, 5L))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void assignReviewerRejectsNonChairNonAdmin() {
        User stranger = new User();
        stranger.setId(30L);
        stranger.setRole(Role.REVIEWER);

        User reviewer = new User();
        reviewer.setId(40L);

        when(committeeService.isChairOrCoChair(stranger, conference)).thenReturn(false);

        assertThatThrownBy(() -> service.assignReviewer(stranger, paper, reviewer))
                .isInstanceOf(SecurityException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.review.service.ReviewAssignmentServiceTest"`
Expected: FAILS to compile — constructor and method signatures don't match yet.

- [ ] **Step 3: Update `ReviewAssignmentService`**

Replace the contents of `src/main/java/org/confcms/cms/review/service/ReviewAssignmentService.java`:

```java
package org.confcms.cms.review.service;

import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.review.domain.AssignmentStatus;
import org.confcms.cms.review.domain.BidType;
import org.confcms.cms.review.domain.ReviewAssignment;
import org.confcms.cms.review.domain.ReviewBid;
import org.confcms.cms.review.repository.ReviewAssignmentRepository;
import org.confcms.cms.review.repository.ReviewBidRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.repository.PaperRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewAssignmentService {

    private final ReviewAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final PaperRepository paperRepository;
    private final ReviewBidRepository bidRepository;
    private final CommitteeService committeeService;

    private void requireChairOrAdmin(User actingUser, Paper paper) {
        boolean isAdmin = actingUser.getRole() == Role.ADMIN;
        if (!isAdmin && !committeeService.isChairOrCoChair(actingUser, paper.getConference())) {
            throw new SecurityException("Not authorized to manage reviewer assignments for this conference");
        }
    }

    @Transactional
    public void submitBid(User reviewer, Long paperId, BidType bidType, String conflictReason) {
        Paper paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        
        ReviewBid bid = bidRepository.findByReviewerIdAndPaperId(reviewer.getId(), paperId)
                .orElse(new ReviewBid());
        
        bid.setReviewer(reviewer);
        bid.setPaper(paper);
        bid.setBidType(bidType);
        bid.setConflictReason(conflictReason);
        
        bidRepository.save(bid);
    }

    @Transactional
    public void autoAssignReviewers(User actingUser, Long paperId) {
        Paper paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        requireChairOrAdmin(actingUser, paper);

        if (paper.getStatus() != PaperStatus.SUBMITTED) {
            throw new IllegalStateException("Paper must be in SUBMITTED state to assign reviewers");
        }

        List<User> reviewers = userRepository.findByRole(Role.REVIEWER);
        List<ReviewBid> bids = bidRepository.findByPaperId(paperId);
        Map<Long, BidType> reviewerBids = bids.stream()
                .collect(Collectors.toMap(b -> b.getReviewer().getId(), ReviewBid::getBidType));

        // Sort reviewers by score
        // Score = Bid Score - (Load * 2)
        reviewers.sort((r1, r2) -> {
            int score1 = calculateScore(r1, reviewerBids.get(r1.getId()));
            int score2 = calculateScore(r2, reviewerBids.get(r2.getId()));
            return Integer.compare(score2, score1); // Descending
        });

        // Assign top 2 reviewers
        int assignedCount = 0;
        for (User reviewer : reviewers) {
            if (assignedCount >= 2) break;
            
            // Skip if conflict or not willing
            BidType bid = reviewerBids.get(reviewer.getId());
            if (bid == BidType.CONFLICT || bid == BidType.NOT_WILLING) continue;

            // Check if already assigned
            boolean alreadyAssigned = assignmentRepository.findByPaperId(paperId).stream()
                    .anyMatch(a -> a.getReviewer().getId().equals(reviewer.getId()));
            
            if (!alreadyAssigned) {
                assignReviewerInternal(paper, reviewer);
                assignedCount++;
            }
        }
        
        paper.setStatus(PaperStatus.UNDER_REVIEW);
        paperRepository.save(paper);
    }

    private int calculateScore(User reviewer, BidType bidType) {
        int score = 0;
        if (bidType != null) {
            switch (bidType) {
                case EAGER -> score += 20;
                case WILLING -> score += 10;
                case NEUTRAL -> score += 0;
                case NOT_WILLING -> score -= 100;
                case CONFLICT -> score -= 1000;
            }
        }
        
        // Load balancing penalty
        int currentLoad = assignmentRepository.findByReviewerId(reviewer.getId()).size();
        score -= (currentLoad * 2);
        
        return score;
    }

    @Transactional
    public void assignReviewer(User actingUser, Paper paper, User reviewer) {
        requireChairOrAdmin(actingUser, paper);
        assignReviewerInternal(paper, reviewer);
    }

    private void assignReviewerInternal(Paper paper, User reviewer) {
        ReviewAssignment assignment = new ReviewAssignment();
        assignment.setPaper(paper);
        assignment.setReviewer(reviewer);
        assignment.setAssignedAt(LocalDateTime.now());
        assignment.setDueDate(LocalDateTime.now().plusWeeks(2));
        assignment.setStatus(AssignmentStatus.PENDING);
        assignmentRepository.save(assignment);
    }
}
```

Note: `assignReviewer` is now authorization-checked and calls a new private `assignReviewerInternal` for the actual insert; `autoAssignReviewers` calls `assignReviewerInternal` directly (not the public `assignReviewer`) since it already checked authorization once at its own entry and re-checking per-reviewer-in-a-loop would be redundant.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.review.service.ReviewAssignmentServiceTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Update `ReviewRestController`**

In `src/main/java/org/confcms/cms/web/controller/ReviewRestController.java`, update the two assignment endpoints. Replace:

```java
    @PostMapping("/assign/auto")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> autoAssign(@RequestParam Long paperId) {
        assignmentService.autoAssignReviewers(paperId);
        return ResponseEntity.ok("Auto-assigned reviewers");
    }

    @PostMapping("/assign/manual")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> manualAssign(@RequestParam Long paperId, @RequestParam Long reviewerId) {
        var paper = paperRepository.findById(paperId).orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        User reviewer = userRepository.findById(reviewerId).orElseThrow(() -> new IllegalArgumentException("Reviewer not found"));
        assignmentService.assignReviewer(paper, reviewer);
        return ResponseEntity.ok("Reviewer assigned");
    }
```

with:

```java
    @PostMapping("/assign/auto")
    @PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
    public ResponseEntity<?> autoAssign(@RequestParam Long paperId) {
        try {
            assignmentService.autoAssignReviewers(actingUser(), paperId);
            return ResponseEntity.ok("Auto-assigned reviewers");
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }

    @PostMapping("/assign/manual")
    @PreAuthorize("hasAnyRole('ADMIN','REVIEWER')")
    public ResponseEntity<?> manualAssign(@RequestParam Long paperId, @RequestParam Long reviewerId) {
        var paper = paperRepository.findById(paperId).orElseThrow(() -> new IllegalArgumentException("Paper not found"));
        User reviewer = userRepository.findById(reviewerId).orElseThrow(() -> new IllegalArgumentException("Reviewer not found"));
        try {
            assignmentService.assignReviewer(actingUser(), paper, reviewer);
            return ResponseEntity.ok("Reviewer assigned");
        } catch (SecurityException se) {
            return ResponseEntity.status(403).body(se.getMessage());
        }
    }
```

Add a private helper method to the same class, matching the pattern already used for `myAssignments`/`submitReview`:

```java
    private User actingUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }
```

- [ ] **Step 6: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/review/service/ReviewAssignmentService.java src/main/java/org/confcms/cms/web/controller/ReviewRestController.java src/test/java/org/confcms/cms/review/service/ReviewAssignmentServiceTest.java
git commit -m "feat: Chair/Co-Chair authorization on reviewer assignment

autoAssignReviewers and assignReviewer now require an acting User and
reject with SecurityException unless ADMIN or Chair/Co-Chair of the
paper's conference. ReviewRestController's assign/auto and
assign/manual endpoints relax from ADMIN-only to hasAnyRole('ADMIN',
'REVIEWER') as the coarse gate, matching the same two-layer pattern
used for DecisionService's endpoints."
```

---

## Task 6: Conference creation requires a Chair

**Files:**
- Modify: `src/main/java/org/confcms/cms/web/controller/AdminConferenceController.java`
- Modify: `src/main/resources/templates/admin/conference_form.html`
- Test: `src/test/java/org/confcms/cms/web/controller/AdminConferenceControllerTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/confcms/cms/web/controller/AdminConferenceControllerTest.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.PaymentProvider;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.ConferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminConferenceControllerTest {

    @Mock
    private ConferenceService conferenceService;
    @Mock
    private CommitteeService committeeService;
    @Mock
    private UserRepository userRepository;

    private AdminConferenceController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminConferenceController(conferenceService, committeeService, userRepository);
    }

    @Test
    void saveConferenceRejectsMissingChair() {
        AdminConferenceController.ConferenceForm form = new AdminConferenceController.ConferenceForm();
        form.setTitle("Test Conf");
        form.setVenue("Venue");
        form.setStartDate(LocalDate.now());
        form.setEndDate(LocalDate.now().plusDays(1));
        form.setContactEmail("a@b.com");
        form.setPaymentProvider(PaymentProvider.FREE);
        form.setChairUserId(null);

        assertThatThrownBy(() -> controller.saveConference(form))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveConferenceAssignsChairWhenProvided() {
        AdminConferenceController.ConferenceForm form = new AdminConferenceController.ConferenceForm();
        form.setTitle("Test Conf");
        form.setVenue("Venue");
        form.setStartDate(LocalDate.now());
        form.setEndDate(LocalDate.now().plusDays(1));
        form.setContactEmail("a@b.com");
        form.setPaymentProvider(PaymentProvider.FREE);
        form.setChairUserId(7L);

        User chairUser = new User();
        chairUser.setId(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(chairUser));
        when(conferenceService.saveConference(any())).thenAnswer(inv -> {
            Conference c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        controller.saveConference(form);

        org.mockito.Mockito.verify(committeeService).assignChair(any(), org.mockito.Mockito.eq(chairUser));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.AdminConferenceControllerTest"`
Expected: FAILS to compile — constructor/form fields don't exist yet.

- [ ] **Step 3: Update `AdminConferenceController`**

Replace the contents of `src/main/java/org/confcms/cms/web/controller/AdminConferenceController.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.domain.CommitteeRole;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.ConferencePaymentConfig;
import org.confcms.cms.domain.PaymentProvider;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.ConferenceService;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/admin/conference")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminConferenceController {

    private final ConferenceService conferenceService;
    private final CommitteeService committeeService;
    private final UserRepository userRepository;

    @GetMapping("/new")
    public String newConferenceForm(Model model) {
        model.addAttribute("conferenceForm", new ConferenceForm());
        model.addAttribute("allUsers", userRepository.findAll());
        return "admin/conference_form";
    }

    @PostMapping("/save")
    public String saveConference(@ModelAttribute ConferenceForm form) {
        if (form.getChairUserId() == null) {
            throw new IllegalArgumentException("A Chair must be selected to create a conference");
        }
        User chairUser = userRepository.findById(form.getChairUserId())
                .orElseThrow(() -> new IllegalArgumentException("Selected Chair not found"));

        Conference conference = new Conference();
        conference.setTitle(form.getTitle());
        conference.setVenue(form.getVenue());
        conference.setStartDate(form.getStartDate());
        conference.setEndDate(form.getEndDate());
        conference.setActive(form.isActive());
        conference.setLogoUrl(form.getLogoUrl());
        conference.setContactEmail(form.getContactEmail());

        ConferencePaymentConfig paymentConfig = new ConferencePaymentConfig();
        paymentConfig.setProvider(form.getPaymentProvider());
        
        if (form.getPaymentProvider() == PaymentProvider.STRIPE) {
            paymentConfig.setStripePublishableKey(form.getStripePublishableKey());
            paymentConfig.setStripeSecretKey(form.getStripeSecretKey());
        } else if (form.getPaymentProvider() == PaymentProvider.PAYPAL) {
            paymentConfig.setPaypalClientId(form.getPaypalClientId());
            paymentConfig.setPaypalClientSecret(form.getPaypalClientSecret());
        } else if (form.getPaymentProvider() == PaymentProvider.LOCAL_BANK) {
            paymentConfig.setBankDetails(form.getBankDetails());
        }

        paymentConfig.setConference(conference);
        conference.setPaymentConfig(paymentConfig);

        Conference saved = conferenceService.saveConference(conference);

        committeeService.assignChair(saved, chairUser);
        for (Long coChairId : form.getCoChairUserIds()) {
            User coChairUser = userRepository.findById(coChairId)
                    .orElseThrow(() -> new IllegalArgumentException("Selected Co-Chair not found"));
            committeeService.addRole(saved, coChairUser, CommitteeRole.CO_CHAIR, null);
        }

        return "redirect:/admin/dashboard";
    }

    @Data
    public static class ConferenceForm {
        private String title;
        private String venue;
        private LocalDate startDate;
        private LocalDate endDate;
        private boolean active;
        private String logoUrl;
        private String contactEmail;

        @NotNull
        private Long chairUserId;
        private List<Long> coChairUserIds = new ArrayList<>();

        // Payment
        private PaymentProvider paymentProvider = PaymentProvider.FREE;
        private String stripePublishableKey;
        private String stripeSecretKey;
        private String paypalClientId;
        private String paypalClientSecret;
        private String bankDetails;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.AdminConferenceControllerTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: Add Chair/Co-Chair selectors to the creation form**

In `src/main/resources/templates/admin/conference_form.html`, insert a new section immediately after the "Set as Active Conference" checkbox block and before the `<hr>` that precedes "Payment Configuration":

```html
                    <hr>

                    <!-- Committee -->
                    <h5 class="mb-3">Committee</h5>
                    <div class="row mb-3">
                        <div class="col-md-6">
                            <label class="form-label">Conference Chair (required)</label>
                            <select class="form-select" th:field="*{chairUserId}" required>
                                <option value="">-- Select Chair --</option>
                                <option th:each="u : ${allUsers}" th:value="${u.id}" th:text="${u.fullName}"></option>
                            </select>
                        </div>
                        <div class="col-md-6">
                            <label class="form-label">Co-Chairs (optional, hold Ctrl/Cmd to select multiple)</label>
                            <select class="form-select" th:field="*{coChairUserIds}" multiple>
                                <option th:each="u : ${allUsers}" th:value="${u.id}" th:text="${u.fullName}"></option>
                            </select>
                        </div>
                    </div>
```

This is placed before the existing `<hr>` that already precedes "Payment Configuration" — the existing `<hr>` currently sits right after the "Set as Active Conference" checkbox block; the new block above adds its own leading `<hr>` and the existing one becomes the separator before Payment Configuration. Verify by reading the file after this edit that there is exactly one `<hr>` between "Committee" and "Payment Configuration" (not two) — remove the duplicate if the insertion point placed one on each side.

- [ ] **Step 6: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Manual verification via running app**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` in the background, then:
- Navigate to `http://localhost:8080/admin/conference/new` (after logging in as the seeded admin) and confirm the Chair dropdown renders and is required.
- Submit the form without selecting a Chair; confirm it does not silently succeed (an unhandled `IllegalArgumentException` will currently surface as a 500 with a stack trace — this is acceptable for this task, since a global exception-handler mapping for validation errors to a friendly page is a separate, pre-existing concern in `GlobalExceptionHandler.java`, not part of this plan's scope).
- Submit the form with a Chair selected; confirm the conference saves and no error occurs.

Stop the app afterward.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/AdminConferenceController.java src/main/resources/templates/admin/conference_form.html src/test/java/org/confcms/cms/web/controller/AdminConferenceControllerTest.java
git commit -m "feat: require Chair selection at conference creation

AdminConferenceController.saveConference now rejects a submission with
no chairUserId before persisting the conference, then calls
CommitteeService.assignChair/addRole to record the Chair and any
Co-Chairs. Selectors are populated from existing Users only -- invite-
by-email for a not-yet-registered Chair is separate future work
(evaluation doc item 2.3), not part of this change."
```

---

## Task 7: Public committee page renders from `ConferenceCommitteeRole`

**Files:**
- Modify: `src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java`
- Modify: `src/main/resources/templates/public/committee.html`

- [ ] **Step 1: Wire `CommitteeService` into `PublicWebController`**

In `src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java`, replace:

```java
package org.confcms.cms.publicweb.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.service.ConferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

@Controller("publicWebController")
@RequiredArgsConstructor
public class PublicWebController {

    private final ConferenceService conferenceService;
```

with:

```java
package org.confcms.cms.publicweb.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.ConferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

@Controller("publicWebController")
@RequiredArgsConstructor
public class PublicWebController {

    private final ConferenceService conferenceService;
    private final CommitteeService committeeService;
```

Then replace the existing `committee` method:

```java
    @GetMapping("/committee")
    public String committee(Model model) {
        return "public/committee";
    }
```

with:

```java
    @GetMapping("/committee")
    public String committee(Model model) {
        Conference activeConference = conferenceService.getActiveConference();
        model.addAttribute("committee", committeeService.getCommitteeForConference(activeConference));
        return "public/committee";
    }
```

(`ConferenceService.getActiveConference()` throws `IllegalStateException` if no conference is active — the existing `addConferenceToModel` `@ModelAttribute` already catches that and sets `conference` to `null` for the page overall, but this new call is a separate, direct call inside the `committee` method. If no conference is active, this will throw before rendering; leaving that as-is matches how every other data-fetching method in this controller already behaves — none of them guard against "no active conference" except the shared `@ModelAttribute`, which is a pre-existing inconsistency in this codebase, not something introduced here.)

- [ ] **Step 2: Update the committee template**

Replace the contents of `src/main/resources/templates/public/committee.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head>
    <meta charset="UTF-8">
    <title>Steering Committee</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/app.css" rel="stylesheet">
</head>

<body>
    <nav class="navbar navbar-expand-lg navbar-dark bg-dark">
        <div class="container">
            <a class="navbar-brand" href="/" th:text="${conference.title}">Conference</a>
        </div>
    </nav>

    <div class="container my-5">
        <h1 class="text-center mb-5">Steering Committee</h1>

        <div class="row">
            <div class="col-md-6 mb-4" th:each="member : ${committee}">
                <div class="card h-100">
                    <div class="card-body">
                        <div class="d-flex align-items-center">
                            <img th:if="${member.photoUrl}" th:src="${member.photoUrl}" alt="Photo" class="rounded-circle me-3"
                                style="width: 100px; height: 100px; object-fit: cover;">
                            <div>
                                <h5 class="card-title" th:text="${member.user.fullName}">Name</h5>
                                <p class="text-muted" th:text="${member.displayTitle ?: member.role.name()}">Role</p>
                            </div>
                        </div>
                        <p class="mt-3" th:if="${member.bio}" th:text="${member.bio}">Bio</p>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <footer class="bg-dark text-white text-center py-4">
        <p>&copy; 2026 <span th:text="${conference.title}">Conference</span></p>
    </footer>
</body>

</html>
```

- [ ] **Step 3: Verify full compilation**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Manual verification via running app**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` in the background. With an active conference and at least one `ConferenceCommitteeRole` row present (create one via the conference-creation flow from Task 6, which now always assigns at least a Chair), navigate to `http://localhost:8080/committee` and confirm the Chair's name and role render without error. Stop the app afterward.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java src/main/resources/templates/public/committee.html
git commit -m "feat: public committee page renders from ConferenceCommitteeRole

Replaces the previously-unwired SteeringCommitteeMember rendering
(committee.html iterated over a model attribute PublicWebController
never actually populated) with real data from CommitteeService,
sourced from the same table that now grants desk-review/reviewer-
assignment permissions -- one source of truth instead of two."
```

---

## Task 8: Delete `SteeringCommitteeMember`

**Files:**
- Delete: `src/main/java/org/confcms/cms/domain/SteeringCommitteeMember.java`

- [ ] **Step 1: Confirm zero remaining references**

Run: `grep -rn "SteeringCommitteeMember" src/main/java src/main/resources`
Expected: no results (Task 2 already removed `Conference`'s reference; no repository for this class ever existed; `committee.html` was rewritten in Task 7 to no longer reference it).

- [ ] **Step 2: Delete the file**

```bash
git rm src/main/java/org/confcms/cms/domain/SteeringCommitteeMember.java
```

- [ ] **Step 3: Verify full build**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: delete SteeringCommitteeMember, fully replaced by ConferenceCommitteeRole

Confirmed zero remaining references. The public committee page and
all authorization checks now run off the single ConferenceCommitteeRole
table."
```

---

## Task 9: Final full-suite verification

**Files:** none changed — verification only.

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew clean test`
Expected: BUILD SUCCESSFUL, all tests pass (`SubmissionServiceTest`, `CommitteeServiceTest`, `DecisionServiceTest`, `ReviewAssignmentServiceTest`, `AdminConferenceControllerTest`, plus the pre-existing `ReviewTest`).

- [ ] **Step 2: Run a full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Start the app and smoke-test the full flow**

Run: `./gradlew bootRun --args='--spring.profiles.active=dev'` in the background.

- Log in as the seeded admin.
- Create a conference, selecting a Chair (and optionally a Co-Chair).
- Visit `/committee` and confirm the Chair renders.
- Confirm `GET /login` still returns 200 (regression check against the earlier domain-model-merge work).

Stop the app afterward. No commit needed for this verification-only task.

---

## Self-review notes (for whoever executes this plan)

- **Task 1 is a hard prerequisite for everything else.** Every later task's authorization check calls `paper.getConference()`; nothing after Task 1 will compile against the old `Paper` shape.
- **This plan follows the codebase's actual canonical package layout** (`domain`/`service`/`repository` flat packages for `Conference`-adjacent code, `submission.domain`/`review.service` for feature-specific code) — confirmed by reading the current file tree before writing this plan, not assumed from the design spec's more general wording.
- **Two roles added in the design spec — `FINANCE_MANAGER`, `REGISTRATION_MANAGER`, `PROCEEDINGS_MANAGER` — have no gated action in this plan.** This is intentional, matching the spec's explicit deferral: the roles exist so future features (payment-slip verification, participant check-in, real proceedings generation) have a permission to check against without another schema migration, but none of those features are built here.
- **`GlobalExceptionHandler`** was noted in Task 6 as not translating the new `IllegalArgumentException` (missing Chair) into a friendly error page — this is a pre-existing gap in how this controller already handles all its other `IllegalArgumentException`s (e.g. "Selected Chair not found"), not something this plan introduces or is scoped to fix.
- **No test framework setup was needed** — `spring-boot-starter-test` (JUnit 5, AssertJ, Mockito) and `spring-boot-starter-validation` are already dependencies in `build.gradle`, confirmed before writing this plan.
