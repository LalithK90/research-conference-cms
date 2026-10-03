# Package-per-Entity Restructuring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move every entity's class, repository, service, controller, and DTO into one flat package per entity (or per agreed aggregate cluster), replacing the current layer-within-feature layout.

**Architecture:** 14 packages, each moved in its own commit, in dependency order (fewest inbound references first). Every file keeps its class name, methods, and fields untouched — only its `package` declaration changes, plus every other file's `import` lines that reference it.

**Tech Stack:** Java 21, Gradle, no IDE refactor tooling in this session — moves are done with `git mv` plus manual `package`/`import` line edits, verified by compilation and the full test suite after every single package.

**Spec:** `docs/superpowers/specs/2026-10-03-package-per-entity-restructuring-design.md`

**Global Constraints (apply to every task below):**
- Never change a class's name, method signature, field name, or any logic. Only `package` and `import` lines change.
- After every task's moves, run `./gradlew test --rerun` and confirm `BUILD SUCCESSFUL` with the exact same test count as before the task (273 at the start of this plan — each task neither adds nor removes tests, since no test logic changes).
- Before moving a file, grep the ENTIRE `src/main/java` and `src/test/java` trees for its fully-qualified old import path (e.g. `org.confcms.cms.domain.User`) to find every file that needs its import line updated. Do not rely on memory of "who imports this" — grep every time, since a prior task in this same plan may have already changed an importer's own package.
- Main and test files for the same class move together in the same task (e.g. `User.java` and `UserTest.java` if one exists).
- Use `git mv` for the move itself (preserves history), then `Edit` for the `package` line in the moved file and for each importer's `import` line.

---

### Task 1: `user` package

**Files to move:**
- `src/main/java/org/confcms/cms/domain/User.java` → `src/main/java/org/confcms/cms/user/User.java`
- `src/main/java/org/confcms/cms/repository/UserRepository.java` → `src/main/java/org/confcms/cms/user/UserRepository.java`

(No dedicated test file for either exists today — confirmed via `find src/test/java -iname "UserTest.java" -o -iname "UserRepositoryTest.java"` returning nothing.)

- [ ] **Step 1: Find every importer**

```bash
grep -rl "org\.confcms\.cms\.domain\.User;" --include="*.java" src/
grep -rl "org\.confcms\.cms\.repository\.UserRepository;" --include="*.java" src/
```

Record the full list of files returned — every one needs its import line updated in Step 3.

- [ ] **Step 2: Move the files**

```bash
mkdir -p src/main/java/org/confcms/cms/user
git mv src/main/java/org/confcms/cms/domain/User.java src/main/java/org/confcms/cms/user/User.java
git mv src/main/java/org/confcms/cms/repository/UserRepository.java src/main/java/org/confcms/cms/user/UserRepository.java
```

- [ ] **Step 3: Update the package declaration in both moved files**

In `src/main/java/org/confcms/cms/user/User.java`, change:
```java
package org.confcms.cms.domain;
```
to:
```java
package org.confcms.cms.user;
```

In `src/main/java/org/confcms/cms/user/UserRepository.java`, change:
```java
package org.confcms.cms.repository;
```
to:
```java
package org.confcms.cms.user;
```

Also check `UserRepository.java`'s own import of `User` — if it currently has `import org.confcms.cms.domain.User;`, remove that import line entirely (both classes are now in the same package, so no import is needed).

- [ ] **Step 4: Update every importer found in Step 1**

For each file in the list from Step 1, change:
```java
import org.confcms.cms.domain.User;
```
to:
```java
import org.confcms.cms.user.User;
```
and:
```java
import org.confcms.cms.repository.UserRepository;
```
to:
```java
import org.confcms.cms.user.UserRepository;
```

- [ ] **Step 5: Compile and run the full suite**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, same test count as baseline (273).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: move User/UserRepository into org.confcms.cms.user package"
```

---

### Task 2: `useridentity` package

**Files to move:**
- `src/main/java/org/confcms/cms/domain/UserIdentity.java` → `src/main/java/org/confcms/cms/useridentity/UserIdentity.java`
- `src/main/java/org/confcms/cms/repository/UserIdentityRepository.java` → `src/main/java/org/confcms/cms/useridentity/UserIdentityRepository.java`

- [ ] **Step 1: Find every importer**

```bash
grep -rl "org\.confcms\.cms\.domain\.UserIdentity;" --include="*.java" src/
grep -rl "org\.confcms\.cms\.repository\.UserIdentityRepository;" --include="*.java" src/
```

- [ ] **Step 2: Move the files**

```bash
mkdir -p src/main/java/org/confcms/cms/useridentity
git mv src/main/java/org/confcms/cms/domain/UserIdentity.java src/main/java/org/confcms/cms/useridentity/UserIdentity.java
git mv src/main/java/org/confcms/cms/repository/UserIdentityRepository.java src/main/java/org/confcms/cms/useridentity/UserIdentityRepository.java
```

- [ ] **Step 3: Update package declarations**

In both moved files, change their `package` line from `org.confcms.cms.domain;` / `org.confcms.cms.repository;` to `org.confcms.cms.useridentity;`. If `UserIdentityRepository.java` imports `UserIdentity` or `User`, update `UserIdentity`'s import to remove it (same package now) and update `User`'s import to `org.confcms.cms.user.User` (per Task 1).

- [ ] **Step 4: Update every importer from Step 1**, changing `org.confcms.cms.domain.UserIdentity` → `org.confcms.cms.useridentity.UserIdentity` and `org.confcms.cms.repository.UserIdentityRepository` → `org.confcms.cms.useridentity.UserIdentityRepository`.

- [ ] **Step 5: Compile and run the full suite**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, 273 tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: move UserIdentity/UserIdentityRepository into org.confcms.cms.useridentity package"
```

---

### Task 3: `accesslog` package

**Files to move:**
- `src/main/java/org/confcms/cms/domain/AccessLog.java` → `src/main/java/org/confcms/cms/accesslog/AccessLog.java`
- `src/main/java/org/confcms/cms/domain/AccessEventType.java` → `src/main/java/org/confcms/cms/accesslog/AccessEventType.java`
- `src/main/java/org/confcms/cms/repository/AccessLogRepository.java` → `src/main/java/org/confcms/cms/accesslog/AccessLogRepository.java`
- `src/main/java/org/confcms/cms/service/AccessLogService.java` → `src/main/java/org/confcms/cms/accesslog/AccessLogService.java`
- `src/main/java/org/confcms/cms/web/controller/AdminAccessLogController.java` → `src/main/java/org/confcms/cms/accesslog/AdminAccessLogController.java`
- `src/test/java/org/confcms/cms/service/AccessLogServiceTest.java` → `src/test/java/org/confcms/cms/accesslog/AccessLogServiceTest.java`
- `src/test/java/org/confcms/cms/web/controller/AdminAccessLogControllerTest.java` → `src/test/java/org/confcms/cms/accesslog/AdminAccessLogControllerTest.java`

- [ ] **Step 1: Find every importer**

```bash
for sym in AccessLog AccessEventType AccessLogRepository AccessLogService AdminAccessLogController; do
  echo "== $sym =="
  grep -rl "org\.confcms\.cms\.\(domain\|repository\|service\|web\.controller\)\.$sym;" --include="*.java" src/
done
```

- [ ] **Step 2: Move main files**

```bash
mkdir -p src/main/java/org/confcms/cms/accesslog
git mv src/main/java/org/confcms/cms/domain/AccessLog.java src/main/java/org/confcms/cms/accesslog/AccessLog.java
git mv src/main/java/org/confcms/cms/domain/AccessEventType.java src/main/java/org/confcms/cms/accesslog/AccessEventType.java
git mv src/main/java/org/confcms/cms/repository/AccessLogRepository.java src/main/java/org/confcms/cms/accesslog/AccessLogRepository.java
git mv src/main/java/org/confcms/cms/service/AccessLogService.java src/main/java/org/confcms/cms/accesslog/AccessLogService.java
git mv src/main/java/org/confcms/cms/web/controller/AdminAccessLogController.java src/main/java/org/confcms/cms/accesslog/AdminAccessLogController.java
```

- [ ] **Step 3: Move test files**

```bash
mkdir -p src/test/java/org/confcms/cms/accesslog
git mv src/test/java/org/confcms/cms/service/AccessLogServiceTest.java src/test/java/org/confcms/cms/accesslog/AccessLogServiceTest.java
git mv src/test/java/org/confcms/cms/web/controller/AdminAccessLogControllerTest.java src/test/java/org/confcms/cms/accesslog/AdminAccessLogControllerTest.java
```

- [ ] **Step 4: Update package declarations** in all 5 moved main files and 2 moved test files to `package org.confcms.cms.accesslog;`. Within this group, remove now-unneeded same-package imports (e.g. `AccessLogService` importing `AccessLog`/`AccessLogRepository`/`AccessEventType`). Any reference to `User` inside this group updates to `org.confcms.cms.user.User`.

- [ ] **Step 5: Update every importer from Step 1** outside this group to the new `org.confcms.cms.accesslog.*` import paths.

- [ ] **Step 6: Compile and run the full suite**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, 273 tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move AccessLog cluster into org.confcms.cms.accesslog package"
```

---

### Task 4: `speaker` package

**Files to move:**
- `src/main/java/org/confcms/cms/domain/Speaker.java` → `src/main/java/org/confcms/cms/speaker/Speaker.java`
- `src/main/java/org/confcms/cms/domain/SpeakerType.java` → `src/main/java/org/confcms/cms/speaker/SpeakerType.java`
- `src/main/java/org/confcms/cms/repository/SpeakerRepository.java` → `src/main/java/org/confcms/cms/speaker/SpeakerRepository.java`
- `src/main/java/org/confcms/cms/web/controller/AdminSpeakerController.java` → `src/main/java/org/confcms/cms/speaker/AdminSpeakerController.java`
- `src/test/java/org/confcms/cms/web/controller/AdminSpeakerControllerTest.java` → `src/test/java/org/confcms/cms/speaker/AdminSpeakerControllerTest.java`

- [ ] **Step 1: Find every importer**

```bash
for sym in Speaker SpeakerType SpeakerRepository AdminSpeakerController; do
  echo "== $sym =="
  grep -rl "org\.confcms\.cms\.\(domain\|repository\|web\.controller\)\.$sym;" --include="*.java" src/
done
```

- [ ] **Step 2: Move main files**

```bash
mkdir -p src/main/java/org/confcms/cms/speaker
git mv src/main/java/org/confcms/cms/domain/Speaker.java src/main/java/org/confcms/cms/speaker/Speaker.java
git mv src/main/java/org/confcms/cms/domain/SpeakerType.java src/main/java/org/confcms/cms/speaker/SpeakerType.java
git mv src/main/java/org/confcms/cms/repository/SpeakerRepository.java src/main/java/org/confcms/cms/speaker/SpeakerRepository.java
git mv src/main/java/org/confcms/cms/web/controller/AdminSpeakerController.java src/main/java/org/confcms/cms/speaker/AdminSpeakerController.java
```

- [ ] **Step 3: Move test file**

```bash
mkdir -p src/test/java/org/confcms/cms/speaker
git mv src/test/java/org/confcms/cms/web/controller/AdminSpeakerControllerTest.java src/test/java/org/confcms/cms/speaker/AdminSpeakerControllerTest.java
```

- [ ] **Step 4: Update package declarations** in all moved files to `package org.confcms.cms.speaker;`. `AdminSpeakerController` likely imports `ConferenceService` (per the Conference cluster, Task 9 below) — leave that import as `org.confcms.cms.service.ConferenceService` for now if Task 9 hasn't run yet in your execution order; if executing strictly in this plan's listed order, Task 9 comes after this one, so leave the import unchanged here and it will be corrected when Task 9 runs (Task 9's Step covering "every importer" will catch this file).

- [ ] **Step 5: Update every importer from Step 1** outside this group to `org.confcms.cms.speaker.*`.

- [ ] **Step 6: Compile and run the full suite**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, 273 tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move Speaker cluster into org.confcms.cms.speaker package"
```

---

### Task 5: `sponsor` package

**Files to move:**
- `src/main/java/org/confcms/cms/domain/Sponsor.java` → `src/main/java/org/confcms/cms/sponsor/Sponsor.java`
- `src/main/java/org/confcms/cms/domain/SponsorTier.java` → `src/main/java/org/confcms/cms/sponsor/SponsorTier.java`
- `src/main/java/org/confcms/cms/repository/SponsorRepository.java` → `src/main/java/org/confcms/cms/sponsor/SponsorRepository.java`
- `src/main/java/org/confcms/cms/web/controller/AdminSponsorController.java` → `src/main/java/org/confcms/cms/sponsor/AdminSponsorController.java`
- `src/test/java/org/confcms/cms/web/controller/AdminSponsorControllerTest.java` → `src/test/java/org/confcms/cms/sponsor/AdminSponsorControllerTest.java`

- [ ] **Step 1: Find every importer**

```bash
for sym in Sponsor SponsorTier SponsorRepository AdminSponsorController; do
  echo "== $sym =="
  grep -rl "org\.confcms\.cms\.\(domain\|repository\|web\.controller\)\.$sym;" --include="*.java" src/
done
```

- [ ] **Step 2: Move main files**

```bash
mkdir -p src/main/java/org/confcms/cms/sponsor
git mv src/main/java/org/confcms/cms/domain/Sponsor.java src/main/java/org/confcms/cms/sponsor/Sponsor.java
git mv src/main/java/org/confcms/cms/domain/SponsorTier.java src/main/java/org/confcms/cms/sponsor/SponsorTier.java
git mv src/main/java/org/confcms/cms/repository/SponsorRepository.java src/main/java/org/confcms/cms/sponsor/SponsorRepository.java
git mv src/main/java/org/confcms/cms/web/controller/AdminSponsorController.java src/main/java/org/confcms/cms/sponsor/AdminSponsorController.java
```

- [ ] **Step 3: Move test file**

```bash
mkdir -p src/test/java/org/confcms/cms/sponsor
git mv src/test/java/org/confcms/cms/web/controller/AdminSponsorControllerTest.java src/test/java/org/confcms/cms/sponsor/AdminSponsorControllerTest.java
```

- [ ] **Step 4: Update package declarations** in all moved files to `package org.confcms.cms.sponsor;`.

- [ ] **Step 5: Update every importer from Step 1** outside this group to `org.confcms.cms.sponsor.*`.

- [ ] **Step 6: Compile and run the full suite**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, 273 tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move Sponsor cluster into org.confcms.cms.sponsor package"
```

---

### Task 6: `personinvitation` package

**Files to move:**
- `src/main/java/org/confcms/cms/domain/PersonInvitation.java` → `src/main/java/org/confcms/cms/personinvitation/PersonInvitation.java`
- `src/main/java/org/confcms/cms/domain/InvitationPurpose.java` → `src/main/java/org/confcms/cms/personinvitation/InvitationPurpose.java`
- `src/main/java/org/confcms/cms/domain/InvitationStatus.java` → `src/main/java/org/confcms/cms/personinvitation/InvitationStatus.java`
- `src/main/java/org/confcms/cms/repository/PersonInvitationRepository.java` → `src/main/java/org/confcms/cms/personinvitation/PersonInvitationRepository.java`
- `src/main/java/org/confcms/cms/service/PersonInvitationService.java` → `src/main/java/org/confcms/cms/personinvitation/PersonInvitationService.java`
- `src/main/java/org/confcms/cms/web/controller/PersonInvitationController.java` → `src/main/java/org/confcms/cms/personinvitation/PersonInvitationController.java`
- `src/main/java/org/confcms/cms/web/controller/PersonInvitationAdminController.java` → `src/main/java/org/confcms/cms/personinvitation/PersonInvitationAdminController.java`
- `src/test/java/org/confcms/cms/service/PersonInvitationServiceTest.java` → `src/test/java/org/confcms/cms/personinvitation/PersonInvitationServiceTest.java`
- `src/test/java/org/confcms/cms/web/controller/PersonInvitationAdminControllerTest.java` → `src/test/java/org/confcms/cms/personinvitation/PersonInvitationAdminControllerTest.java`

- [ ] **Step 1: Find every importer**

```bash
for sym in PersonInvitation InvitationPurpose InvitationStatus PersonInvitationRepository PersonInvitationService PersonInvitationController PersonInvitationAdminController; do
  echo "== $sym =="
  grep -rl "org\.confcms\.cms\.\(domain\|repository\|service\|web\.controller\)\.$sym;" --include="*.java" src/
done
```

- [ ] **Step 2: Move main files**

```bash
mkdir -p src/main/java/org/confcms/cms/personinvitation
git mv src/main/java/org/confcms/cms/domain/PersonInvitation.java src/main/java/org/confcms/cms/personinvitation/PersonInvitation.java
git mv src/main/java/org/confcms/cms/domain/InvitationPurpose.java src/main/java/org/confcms/cms/personinvitation/InvitationPurpose.java
git mv src/main/java/org/confcms/cms/domain/InvitationStatus.java src/main/java/org/confcms/cms/personinvitation/InvitationStatus.java
git mv src/main/java/org/confcms/cms/repository/PersonInvitationRepository.java src/main/java/org/confcms/cms/personinvitation/PersonInvitationRepository.java
git mv src/main/java/org/confcms/cms/service/PersonInvitationService.java src/main/java/org/confcms/cms/personinvitation/PersonInvitationService.java
git mv src/main/java/org/confcms/cms/web/controller/PersonInvitationController.java src/main/java/org/confcms/cms/personinvitation/PersonInvitationController.java
git mv src/main/java/org/confcms/cms/web/controller/PersonInvitationAdminController.java src/main/java/org/confcms/cms/personinvitation/PersonInvitationAdminController.java
```

- [ ] **Step 3: Move test files**

```bash
mkdir -p src/test/java/org/confcms/cms/personinvitation
git mv src/test/java/org/confcms/cms/service/PersonInvitationServiceTest.java src/test/java/org/confcms/cms/personinvitation/PersonInvitationServiceTest.java
git mv src/test/java/org/confcms/cms/web/controller/PersonInvitationAdminControllerTest.java src/test/java/org/confcms/cms/personinvitation/PersonInvitationAdminControllerTest.java
```

- [ ] **Step 4: Update package declarations** in all 7 moved main files and 2 moved test files to `package org.confcms.cms.personinvitation;`. `PersonInvitationService.inviteCoAuthor` takes a `Paper`/`PaperAuthor` (per the design spec's §3 ambiguous-files note) — its import of those updates to `org.confcms.cms.paper.Paper`/`org.confcms.cms.paper.PaperAuthor` once Task 10 (`paper` package) has run; if running strictly in this plan's order, Task 10 comes after this one, so leave that import as-is here (it will be corrected during Task 10's "every importer" step). Its `User`-related import updates to `org.confcms.cms.user.User` now (Task 1 already ran).

- [ ] **Step 5: Update every importer from Step 1** outside this group to `org.confcms.cms.personinvitation.*`.

- [ ] **Step 6: Compile and run the full suite**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, 273 tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move PersonInvitation cluster into org.confcms.cms.personinvitation package"
```

---

### Task 7: `conflictdeclaration` package

**Files to move:**
- `src/main/java/org/confcms/cms/domain/ConflictDeclaration.java` → `src/main/java/org/confcms/cms/conflictdeclaration/ConflictDeclaration.java`
- `src/main/java/org/confcms/cms/repository/ConflictDeclarationRepository.java` → `src/main/java/org/confcms/cms/conflictdeclaration/ConflictDeclarationRepository.java`
- `src/main/java/org/confcms/cms/service/ConflictDeclarationService.java` → `src/main/java/org/confcms/cms/conflictdeclaration/ConflictDeclarationService.java`
- `src/main/java/org/confcms/cms/web/controller/ConflictDeclarationController.java` → `src/main/java/org/confcms/cms/conflictdeclaration/ConflictDeclarationController.java`
- `src/test/java/org/confcms/cms/service/ConflictDeclarationServiceTest.java` → `src/test/java/org/confcms/cms/conflictdeclaration/ConflictDeclarationServiceTest.java`

(No dedicated controller test file found for `ConflictDeclarationController` — confirmed via the `find src/test` listing above; skip moving one that doesn't exist.)

- [ ] **Step 1: Find every importer**

```bash
for sym in ConflictDeclaration ConflictDeclarationRepository ConflictDeclarationService ConflictDeclarationController; do
  echo "== $sym =="
  grep -rl "org\.confcms\.cms\.\(domain\|repository\|service\|web\.controller\)\.$sym;" --include="*.java" src/
done
```

- [ ] **Step 2: Move main files**

```bash
mkdir -p src/main/java/org/confcms/cms/conflictdeclaration
git mv src/main/java/org/confcms/cms/domain/ConflictDeclaration.java src/main/java/org/confcms/cms/conflictdeclaration/ConflictDeclaration.java
git mv src/main/java/org/confcms/cms/repository/ConflictDeclarationRepository.java src/main/java/org/confcms/cms/conflictdeclaration/ConflictDeclarationRepository.java
git mv src/main/java/org/confcms/cms/service/ConflictDeclarationService.java src/main/java/org/confcms/cms/conflictdeclaration/ConflictDeclarationService.java
git mv src/main/java/org/confcms/cms/web/controller/ConflictDeclarationController.java src/main/java/org/confcms/cms/conflictdeclaration/ConflictDeclarationController.java
```

- [ ] **Step 3: Move test file**

```bash
mkdir -p src/test/java/org/confcms/cms/conflictdeclaration
git mv src/test/java/org/confcms/cms/service/ConflictDeclarationServiceTest.java src/test/java/org/confcms/cms/conflictdeclaration/ConflictDeclarationServiceTest.java
```

- [ ] **Step 4: Update package declarations** in all 4 moved main files and 1 moved test file to `package org.confcms.cms.conflictdeclaration;`. Update any `Paper`/`User` references to their new packages (`Paper` only if Task 10 has already run — otherwise leave for Task 10 to catch).

- [ ] **Step 5: Update every importer from Step 1** outside this group to `org.confcms.cms.conflictdeclaration.*`.

- [ ] **Step 6: Compile and run the full suite**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, 273 tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move ConflictDeclaration cluster into org.confcms.cms.conflictdeclaration package"
```

---

### Task 8: `auth` package

**Files to move:**
- `src/main/java/org/confcms/cms/domain/MagicLink.java` → `src/main/java/org/confcms/cms/auth/MagicLink.java`
- `src/main/java/org/confcms/cms/domain/PasswordResetToken.java` → `src/main/java/org/confcms/cms/auth/PasswordResetToken.java`
- `src/main/java/org/confcms/cms/repository/MagicLinkRepository.java` → `src/main/java/org/confcms/cms/auth/MagicLinkRepository.java`
- `src/main/java/org/confcms/cms/repository/PasswordResetTokenRepository.java` → `src/main/java/org/confcms/cms/auth/PasswordResetTokenRepository.java`
- `src/main/java/org/confcms/cms/auth/service/AuthService.java` → `src/main/java/org/confcms/cms/auth/AuthService.java`
- `src/main/java/org/confcms/cms/auth/service/MagicLinkService.java` → `src/main/java/org/confcms/cms/auth/MagicLinkService.java`
- `src/main/java/org/confcms/cms/auth/service/PasswordResetService.java` → `src/main/java/org/confcms/cms/auth/PasswordResetService.java`
- `src/main/java/org/confcms/cms/web/controller/AuthRestController.java` → `src/main/java/org/confcms/cms/auth/AuthRestController.java`
- `src/main/java/org/confcms/cms/web/controller/PasswordResetController.java` → `src/main/java/org/confcms/cms/auth/PasswordResetController.java`
- `src/main/java/org/confcms/cms/web/controller/AccountSettingsController.java` → `src/main/java/org/confcms/cms/auth/AccountSettingsController.java`
- `src/main/java/org/confcms/cms/web/controller/AdminUserController.java` → `src/main/java/org/confcms/cms/auth/AdminUserController.java`
- `src/test/java/org/confcms/cms/auth/service/AuthServiceTest.java` → `src/test/java/org/confcms/cms/auth/AuthServiceTest.java`
- `src/test/java/org/confcms/cms/auth/service/MagicLinkServiceTest.java` → `src/test/java/org/confcms/cms/auth/MagicLinkServiceTest.java`
- `src/test/java/org/confcms/cms/auth/service/PasswordResetServiceTest.java` → `src/test/java/org/confcms/cms/auth/PasswordResetServiceTest.java`
- `src/test/java/org/confcms/cms/web/controller/AccountSettingsControllerTest.java` → `src/test/java/org/confcms/cms/auth/AccountSettingsControllerTest.java`
- `src/test/java/org/confcms/cms/web/controller/AdminUserControllerTest.java` → `src/test/java/org/confcms/cms/auth/AdminUserControllerTest.java`

This is the largest single-task move so far (11 main files, 5 test files) — take extra care with Step 1's grep since `AuthService`/`MagicLinkService`/`PasswordResetService` are referenced from `security/` (unchanged location) and possibly other clusters.

- [ ] **Step 1: Find every importer**

```bash
for sym in MagicLink PasswordResetToken MagicLinkRepository PasswordResetTokenRepository AuthService MagicLinkService PasswordResetService AuthRestController PasswordResetController AccountSettingsController AdminUserController; do
  echo "== $sym =="
  grep -rl "org\.confcms\.cms\.\(domain\|repository\|auth\.service\|web\.controller\)\.$sym;" --include="*.java" src/
done
```

- [ ] **Step 2: Move main files**

```bash
mkdir -p src/main/java/org/confcms/cms/auth
git mv src/main/java/org/confcms/cms/domain/MagicLink.java src/main/java/org/confcms/cms/auth/MagicLink.java
git mv src/main/java/org/confcms/cms/domain/PasswordResetToken.java src/main/java/org/confcms/cms/auth/PasswordResetToken.java
git mv src/main/java/org/confcms/cms/repository/MagicLinkRepository.java src/main/java/org/confcms/cms/auth/MagicLinkRepository.java
git mv src/main/java/org/confcms/cms/repository/PasswordResetTokenRepository.java src/main/java/org/confcms/cms/auth/PasswordResetTokenRepository.java
git mv src/main/java/org/confcms/cms/auth/service/AuthService.java src/main/java/org/confcms/cms/auth/AuthService.java
git mv src/main/java/org/confcms/cms/auth/service/MagicLinkService.java src/main/java/org/confcms/cms/auth/MagicLinkService.java
git mv src/main/java/org/confcms/cms/auth/service/PasswordResetService.java src/main/java/org/confcms/cms/auth/PasswordResetService.java
git mv src/main/java/org/confcms/cms/web/controller/AuthRestController.java src/main/java/org/confcms/cms/auth/AuthRestController.java
git mv src/main/java/org/confcms/cms/web/controller/PasswordResetController.java src/main/java/org/confcms/cms/auth/PasswordResetController.java
git mv src/main/java/org/confcms/cms/web/controller/AccountSettingsController.java src/main/java/org/confcms/cms/auth/AccountSettingsController.java
git mv src/main/java/org/confcms/cms/web/controller/AdminUserController.java src/main/java/org/confcms/cms/auth/AdminUserController.java
rmdir src/main/java/org/confcms/cms/auth/service 2>/dev/null || true
```

- [ ] **Step 3: Move test files**

```bash
mkdir -p src/test/java/org/confcms/cms/auth
git mv src/test/java/org/confcms/cms/auth/service/AuthServiceTest.java src/test/java/org/confcms/cms/auth/AuthServiceTest.java
git mv src/test/java/org/confcms/cms/auth/service/MagicLinkServiceTest.java src/test/java/org/confcms/cms/auth/MagicLinkServiceTest.java
git mv src/test/java/org/confcms/cms/auth/service/PasswordResetServiceTest.java src/test/java/org/confcms/cms/auth/PasswordResetServiceTest.java
git mv src/test/java/org/confcms/cms/web/controller/AccountSettingsControllerTest.java src/test/java/org/confcms/cms/auth/AccountSettingsControllerTest.java
git mv src/test/java/org/confcms/cms/web/controller/AdminUserControllerTest.java src/test/java/org/confcms/cms/auth/AdminUserControllerTest.java
rmdir src/test/java/org/confcms/cms/auth/service 2>/dev/null || true
```

- [ ] **Step 4: Update package declarations** in all 11 moved main files and 5 moved test files to `package org.confcms.cms.auth;`. Remove now-redundant same-package imports within this group. `User` references update to `org.confcms.cms.user.User`.

- [ ] **Step 5: Update every importer from Step 1** outside this group. Note `security/MagicLinkAuthenticationProvider.java` and `security/MagicLinkAuthenticationFilter.java` (unchanged location) almost certainly import `MagicLinkService`/`MagicLink` — these import lines must change to `org.confcms.cms.auth.MagicLinkService`/`org.confcms.cms.auth.MagicLink` even though the importing file itself doesn't move. Same check for `security/CustomUserDetailsService.java` and `FirstRunAdminInitializer.java` against `User`.

- [ ] **Step 6: Compile and run the full suite**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, 273 tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move auth cluster (User/MagicLink/PasswordReset flows) into org.confcms.cms.auth package"
```

---

### Task 9: `conference` package

**Files to move:**
- `src/main/java/org/confcms/cms/domain/Conference.java` → `src/main/java/org/confcms/cms/conference/Conference.java`
- `src/main/java/org/confcms/cms/domain/ConferenceCommitteeRole.java` → `src/main/java/org/confcms/cms/conference/ConferenceCommitteeRole.java`
- `src/main/java/org/confcms/cms/domain/ConferencePaymentConfig.java` → `src/main/java/org/confcms/cms/conference/ConferencePaymentConfig.java`
- `src/main/java/org/confcms/cms/domain/SubTheme.java` → `src/main/java/org/confcms/cms/conference/SubTheme.java`
- `src/main/java/org/confcms/cms/domain/CommitteeRole.java` → `src/main/java/org/confcms/cms/conference/CommitteeRole.java`
- `src/main/java/org/confcms/cms/domain/PaymentProvider.java` → `src/main/java/org/confcms/cms/conference/PaymentProvider.java`
- `src/main/java/org/confcms/cms/domain/PaymentSecretConverter.java` → `src/main/java/org/confcms/cms/conference/PaymentSecretConverter.java`
- `src/main/java/org/confcms/cms/repository/ConferenceRepository.java` → `src/main/java/org/confcms/cms/conference/ConferenceRepository.java`
- `src/main/java/org/confcms/cms/repository/ConferenceCommitteeRoleRepository.java` → `src/main/java/org/confcms/cms/conference/ConferenceCommitteeRoleRepository.java`
- `src/main/java/org/confcms/cms/repository/ConferencePaymentConfigRepository.java` → `src/main/java/org/confcms/cms/conference/ConferencePaymentConfigRepository.java`
- `src/main/java/org/confcms/cms/service/ConferenceService.java` → `src/main/java/org/confcms/cms/conference/ConferenceService.java`
- `src/main/java/org/confcms/cms/service/CommitteeService.java` → `src/main/java/org/confcms/cms/conference/CommitteeService.java`
- `src/main/java/org/confcms/cms/web/controller/AdminConferenceController.java` → `src/main/java/org/confcms/cms/conference/AdminConferenceController.java`
- `src/test/java/org/confcms/cms/domain/PaymentSecretConverterTest.java` → `src/test/java/org/confcms/cms/conference/PaymentSecretConverterTest.java`
- `src/test/java/org/confcms/cms/service/CommitteeServiceTest.java` → `src/test/java/org/confcms/cms/conference/CommitteeServiceTest.java`
- `src/test/java/org/confcms/cms/web/controller/AdminConferenceControllerTest.java` → `src/test/java/org/confcms/cms/conference/AdminConferenceControllerTest.java`

(No dedicated `ConferenceServiceTest` found in the current test listing — confirmed, skip.)

- [ ] **Step 1: Find every importer**

```bash
for sym in Conference ConferenceCommitteeRole ConferencePaymentConfig SubTheme CommitteeRole PaymentProvider PaymentSecretConverter ConferenceRepository ConferenceCommitteeRoleRepository ConferencePaymentConfigRepository ConferenceService CommitteeService AdminConferenceController; do
  echo "== $sym =="
  grep -rl "org\.confcms\.cms\.\(domain\|repository\|service\|web\.controller\)\.$sym;" --include="*.java" src/
done
```

- [ ] **Step 2: Move main files**

```bash
mkdir -p src/main/java/org/confcms/cms/conference
git mv src/main/java/org/confcms/cms/domain/Conference.java src/main/java/org/confcms/cms/conference/Conference.java
git mv src/main/java/org/confcms/cms/domain/ConferenceCommitteeRole.java src/main/java/org/confcms/cms/conference/ConferenceCommitteeRole.java
git mv src/main/java/org/confcms/cms/domain/ConferencePaymentConfig.java src/main/java/org/confcms/cms/conference/ConferencePaymentConfig.java
git mv src/main/java/org/confcms/cms/domain/SubTheme.java src/main/java/org/confcms/cms/conference/SubTheme.java
git mv src/main/java/org/confcms/cms/domain/CommitteeRole.java src/main/java/org/confcms/cms/conference/CommitteeRole.java
git mv src/main/java/org/confcms/cms/domain/PaymentProvider.java src/main/java/org/confcms/cms/conference/PaymentProvider.java
git mv src/main/java/org/confcms/cms/domain/PaymentSecretConverter.java src/main/java/org/confcms/cms/conference/PaymentSecretConverter.java
git mv src/main/java/org/confcms/cms/repository/ConferenceRepository.java src/main/java/org/confcms/cms/conference/ConferenceRepository.java
git mv src/main/java/org/confcms/cms/repository/ConferenceCommitteeRoleRepository.java src/main/java/org/confcms/cms/conference/ConferenceCommitteeRoleRepository.java
git mv src/main/java/org/confcms/cms/repository/ConferencePaymentConfigRepository.java src/main/java/org/confcms/cms/conference/ConferencePaymentConfigRepository.java
git mv src/main/java/org/confcms/cms/service/ConferenceService.java src/main/java/org/confcms/cms/conference/ConferenceService.java
git mv src/main/java/org/confcms/cms/service/CommitteeService.java src/main/java/org/confcms/cms/conference/CommitteeService.java
git mv src/main/java/org/confcms/cms/web/controller/AdminConferenceController.java src/main/java/org/confcms/cms/conference/AdminConferenceController.java
```

- [ ] **Step 3: Move test files**

```bash
mkdir -p src/test/java/org/confcms/cms/conference
git mv src/test/java/org/confcms/cms/domain/PaymentSecretConverterTest.java src/test/java/org/confcms/cms/conference/PaymentSecretConverterTest.java
git mv src/test/java/org/confcms/cms/service/CommitteeServiceTest.java src/test/java/org/confcms/cms/conference/CommitteeServiceTest.java
git mv src/test/java/org/confcms/cms/web/controller/AdminConferenceControllerTest.java src/test/java/org/confcms/cms/conference/AdminConferenceControllerTest.java
```

- [ ] **Step 4: Update package declarations** in all 13 moved main files and 3 moved test files to `package org.confcms.cms.conference;`. Remove now-redundant same-package imports within this group (e.g. `AdminConferenceController` importing `Conference`/`ConferenceCommitteeRole`/`ConferencePaymentConfig`/`SubTheme`/`CommitteeRole`/`PaymentProvider`). `User`/`UserRepository` references (used by `AdminConferenceController` for chair/co-chair selection) update to `org.confcms.cms.user.*`. Any `Speaker`/`Sponsor` cross-references in `AdminConferenceController` (if present, per the design spec's note that `PublicWebController` reads committee data) — check and update to `org.confcms.cms.speaker.*`/`org.confcms.cms.sponsor.*` if found.

- [ ] **Step 5: Update every importer from Step 1** outside this group. This group is heavily referenced — `PublicWebController`, `AuthorDashboardController`, `DashboardController`, and most feature services will import `Conference`/`ConferenceService`. Expect this step to touch 15+ files.

- [ ] **Step 6: Compile and run the full suite**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, 273 tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move Conference aggregate (CommitteeRole/PaymentConfig/SubTheme) into org.confcms.cms.conference package"
```

---

### Task 10: `paper` package

**Files to move:**
- `src/main/java/org/confcms/cms/submission/domain/Paper.java` → `src/main/java/org/confcms/cms/paper/Paper.java`
- `src/main/java/org/confcms/cms/submission/domain/PaperAuthor.java` → `src/main/java/org/confcms/cms/paper/PaperAuthor.java`
- `src/main/java/org/confcms/cms/submission/domain/PaperVersion.java` → `src/main/java/org/confcms/cms/paper/PaperVersion.java`
- `src/main/java/org/confcms/cms/submission/domain/PaperStatus.java` → `src/main/java/org/confcms/cms/paper/PaperStatus.java`
- `src/main/java/org/confcms/cms/submission/domain/RevisionResolution.java` → `src/main/java/org/confcms/cms/paper/RevisionResolution.java`
- `src/main/java/org/confcms/cms/submission/repository/PaperRepository.java` → `src/main/java/org/confcms/cms/paper/PaperRepository.java`
- `src/main/java/org/confcms/cms/submission/repository/PaperVersionRepository.java` → `src/main/java/org/confcms/cms/paper/PaperVersionRepository.java`
- `src/main/java/org/confcms/cms/submission/service/SubmissionService.java` → `src/main/java/org/confcms/cms/paper/SubmissionService.java`
- `src/main/java/org/confcms/cms/submission/dto/AuthorRequestDto.java` → `src/main/java/org/confcms/cms/paper/AuthorRequestDto.java`
- `src/main/java/org/confcms/cms/submission/dto/ConferenceSummaryDto.java` → `src/main/java/org/confcms/cms/paper/ConferenceSummaryDto.java`
- `src/main/java/org/confcms/cms/submission/dto/PaperAuthorDto.java` → `src/main/java/org/confcms/cms/paper/PaperAuthorDto.java`
- `src/main/java/org/confcms/cms/submission/dto/PaperResponseDto.java` → `src/main/java/org/confcms/cms/paper/PaperResponseDto.java`
- `src/main/java/org/confcms/cms/submission/dto/PaperVersionDto.java` → `src/main/java/org/confcms/cms/paper/PaperVersionDto.java`
- `src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java` → `src/main/java/org/confcms/cms/paper/SubmissionRestController.java`
- `src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java` → `src/test/java/org/confcms/cms/paper/SubmissionServiceTest.java`
- `src/test/java/org/confcms/cms/submission/dto/ConferenceSummaryDtoTest.java` → `src/test/java/org/confcms/cms/paper/ConferenceSummaryDtoTest.java`
- `src/test/java/org/confcms/cms/submission/dto/PaperAuthorDtoTest.java` → `src/test/java/org/confcms/cms/paper/PaperAuthorDtoTest.java`
- `src/test/java/org/confcms/cms/submission/dto/PaperResponseDtoTest.java` → `src/test/java/org/confcms/cms/paper/PaperResponseDtoTest.java`
- `src/test/java/org/confcms/cms/submission/dto/PaperVersionDtoTest.java` → `src/test/java/org/confcms/cms/paper/PaperVersionDtoTest.java`
- `src/test/java/org/confcms/cms/web/controller/SubmissionRestControllerTest.java` → `src/test/java/org/confcms/cms/paper/SubmissionRestControllerTest.java`
- `src/test/java/org/confcms/cms/web/controller/SubmissionRestControllerSerializationTest.java` → `src/test/java/org/confcms/cms/paper/SubmissionRestControllerSerializationTest.java`

This is the second-largest task (14 main files, 7 test files, including the 4 DTOs just added by the previous plan). Note the Conference-DTO naming collision risk: `ConferenceSummaryDto` lives in this package even though it summarizes `Conference` (per the already-approved design — it belongs with `Paper`'s response shape, not with `Conference` itself, since nothing in the `conference` package produces or consumes it).

- [ ] **Step 1: Find every importer**

```bash
for sym in Paper PaperAuthor PaperVersion PaperStatus RevisionResolution PaperRepository PaperVersionRepository SubmissionService AuthorRequestDto ConferenceSummaryDto PaperAuthorDto PaperResponseDto PaperVersionDto SubmissionRestController; do
  echo "== $sym =="
  grep -rl "org\.confcms\.cms\.\(submission\.domain\|submission\.repository\|submission\.service\|submission\.dto\|web\.controller\)\.$sym;" --include="*.java" src/
done
```

- [ ] **Step 2: Move main files**

```bash
mkdir -p src/main/java/org/confcms/cms/paper
git mv src/main/java/org/confcms/cms/submission/domain/Paper.java src/main/java/org/confcms/cms/paper/Paper.java
git mv src/main/java/org/confcms/cms/submission/domain/PaperAuthor.java src/main/java/org/confcms/cms/paper/PaperAuthor.java
git mv src/main/java/org/confcms/cms/submission/domain/PaperVersion.java src/main/java/org/confcms/cms/paper/PaperVersion.java
git mv src/main/java/org/confcms/cms/submission/domain/PaperStatus.java src/main/java/org/confcms/cms/paper/PaperStatus.java
git mv src/main/java/org/confcms/cms/submission/domain/RevisionResolution.java src/main/java/org/confcms/cms/paper/RevisionResolution.java
git mv src/main/java/org/confcms/cms/submission/repository/PaperRepository.java src/main/java/org/confcms/cms/paper/PaperRepository.java
git mv src/main/java/org/confcms/cms/submission/repository/PaperVersionRepository.java src/main/java/org/confcms/cms/paper/PaperVersionRepository.java
git mv src/main/java/org/confcms/cms/submission/service/SubmissionService.java src/main/java/org/confcms/cms/paper/SubmissionService.java
git mv src/main/java/org/confcms/cms/submission/dto/AuthorRequestDto.java src/main/java/org/confcms/cms/paper/AuthorRequestDto.java
git mv src/main/java/org/confcms/cms/submission/dto/ConferenceSummaryDto.java src/main/java/org/confcms/cms/paper/ConferenceSummaryDto.java
git mv src/main/java/org/confcms/cms/submission/dto/PaperAuthorDto.java src/main/java/org/confcms/cms/paper/PaperAuthorDto.java
git mv src/main/java/org/confcms/cms/submission/dto/PaperResponseDto.java src/main/java/org/confcms/cms/paper/PaperResponseDto.java
git mv src/main/java/org/confcms/cms/submission/dto/PaperVersionDto.java src/main/java/org/confcms/cms/paper/PaperVersionDto.java
git mv src/main/java/org/confcms/cms/web/controller/SubmissionRestController.java src/main/java/org/confcms/cms/paper/SubmissionRestController.java
rmdir src/main/java/org/confcms/cms/submission/domain src/main/java/org/confcms/cms/submission/repository src/main/java/org/confcms/cms/submission/service src/main/java/org/confcms/cms/submission/dto src/main/java/org/confcms/cms/submission 2>/dev/null || true
```

- [ ] **Step 3: Move test files**

```bash
mkdir -p src/test/java/org/confcms/cms/paper
git mv src/test/java/org/confcms/cms/submission/service/SubmissionServiceTest.java src/test/java/org/confcms/cms/paper/SubmissionServiceTest.java
git mv src/test/java/org/confcms/cms/submission/dto/ConferenceSummaryDtoTest.java src/test/java/org/confcms/cms/paper/ConferenceSummaryDtoTest.java
git mv src/test/java/org/confcms/cms/submission/dto/PaperAuthorDtoTest.java src/test/java/org/confcms/cms/paper/PaperAuthorDtoTest.java
git mv src/test/java/org/confcms/cms/submission/dto/PaperResponseDtoTest.java src/test/java/org/confcms/cms/paper/PaperResponseDtoTest.java
git mv src/test/java/org/confcms/cms/submission/dto/PaperVersionDtoTest.java src/test/java/org/confcms/cms/paper/PaperVersionDtoTest.java
git mv src/test/java/org/confcms/cms/web/controller/SubmissionRestControllerTest.java src/test/java/org/confcms/cms/paper/SubmissionRestControllerTest.java
git mv src/test/java/org/confcms/cms/web/controller/SubmissionRestControllerSerializationTest.java src/test/java/org/confcms/cms/paper/SubmissionRestControllerSerializationTest.java
rmdir src/test/java/org/confcms/cms/submission/service src/test/java/org/confcms/cms/submission/dto src/test/java/org/confcms/cms/submission 2>/dev/null || true
```

- [ ] **Step 4: Update package declarations** in all 14 moved main files and 7 moved test files to `package org.confcms.cms.paper;`. Remove now-redundant same-package imports within this group (this is the biggest cleanup in the whole plan — `SubmissionService`, `SubmissionRestController`, and all 4 response DTOs cross-reference `Paper`/`PaperVersion`/`PaperAuthor`/`PaperStatus` constantly). `Conference`, `User`, `UserRepository`, `FileStorageService`, `EmailService` references update to their respective new/unchanged packages (`org.confcms.cms.conference.Conference`, `org.confcms.cms.user.User`/`UserRepository`, `org.confcms.cms.service.FileStorageService`/`EmailService` unchanged).

- [ ] **Step 5: Update every importer from Step 1** outside this group. Expect `DecisionService`, `AdminController`, `AdminDecisionController`, `AdminDecisionViewController`, `ReviewService`, `ReviewAssignmentService`, `AuthorDashboardController`, `PersonInvitationService`, `ProceedingsService`, and `SchedulingService` to all need import updates here, per the design spec's §3 ambiguous-files list — this is the task most likely to touch files outside this plan's other clusters, so re-run Step 1's grep a second time after this step to confirm nothing was missed.

- [ ] **Step 6: Compile and run the full suite**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, 273 tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move Paper aggregate (PaperVersion/PaperAuthor/DTOs) into org.confcms.cms.paper package"
```

---

### Task 11: `review` package (flatten existing subfolders)

**Files to move** (within `src/main/java/org/confcms/cms/review/`, flattening `domain/`, `repository/`, `service/`, `dto/` into the package root; plus absorbing `ReviewRestController` from the top-level `web/controller`):

- `review/domain/Review.java` → `review/Review.java`
- `review/domain/ReviewAssignment.java` → `review/ReviewAssignment.java`
- `review/domain/ReviewBid.java` → `review/ReviewBid.java`
- `review/domain/ReviewDecline.java` → `review/ReviewDecline.java`
- `review/domain/AssignmentStatus.java` → `review/AssignmentStatus.java`
- `review/domain/BidType.java` → `review/BidType.java`
- `review/domain/ReviewDecision.java` → `review/ReviewDecision.java`
- `review/repository/ReviewRepository.java` → `review/ReviewRepository.java`
- `review/repository/ReviewAssignmentRepository.java` → `review/ReviewAssignmentRepository.java`
- `review/repository/ReviewBidRepository.java` → `review/ReviewBidRepository.java`
- `review/repository/ReviewDeclineRepository.java` → `review/ReviewDeclineRepository.java`
- `review/service/ReviewService.java` → `review/ReviewService.java`
- `review/service/ReviewAssignmentService.java` → `review/ReviewAssignmentService.java`
- `review/dto/PaperReviewView.java` → `review/PaperReviewView.java`
- `web/controller/ReviewRestController.java` → `review/ReviewRestController.java`

Test files, confirm exact current paths first via `find src/test/java/org/confcms/cms/review -name "*.java"` (already confirmed above: `review/domain/ReviewTest.java`, `review/service/ReviewAssignmentServiceTest.java`, `review/service/ReviewServiceTest.java`) plus `web/controller/ReviewRestControllerTest.java`:

- `review/domain/ReviewTest.java` → `review/ReviewTest.java`
- `review/service/ReviewAssignmentServiceTest.java` → `review/ReviewAssignmentServiceTest.java`
- `review/service/ReviewServiceTest.java` → `review/ReviewServiceTest.java`
- `web/controller/ReviewRestControllerTest.java` → `review/ReviewRestControllerTest.java`

- [ ] **Step 1: Find every importer outside the `review` package itself** (imports from within `review.domain`/`review.repository`/`review.service`/`review.dto` to each other are handled in Step 4, not here)

```bash
for sym in Review ReviewAssignment ReviewBid ReviewDecline AssignmentStatus BidType ReviewDecision ReviewRepository ReviewAssignmentRepository ReviewBidRepository ReviewDeclineRepository ReviewService ReviewAssignmentService PaperReviewView ReviewRestController; do
  echo "== $sym =="
  grep -rl "org\.confcms\.cms\.review\.\(domain\|repository\|service\|dto\)\.$sym;" --include="*.java" src/ | grep -v "^src/main/java/org/confcms/cms/review/" | grep -v "^src/test/java/org/confcms/cms/review/"
done
grep -rl "org\.confcms\.cms\.web\.controller\.ReviewRestController;" --include="*.java" src/
```

- [ ] **Step 2: Move main files**

```bash
cd src/main/java/org/confcms/cms/review
git mv domain/Review.java Review.java
git mv domain/ReviewAssignment.java ReviewAssignment.java
git mv domain/ReviewBid.java ReviewBid.java
git mv domain/ReviewDecline.java ReviewDecline.java
git mv domain/AssignmentStatus.java AssignmentStatus.java
git mv domain/BidType.java BidType.java
git mv domain/ReviewDecision.java ReviewDecision.java
git mv repository/ReviewRepository.java ReviewRepository.java
git mv repository/ReviewAssignmentRepository.java ReviewAssignmentRepository.java
git mv repository/ReviewBidRepository.java ReviewBidRepository.java
git mv repository/ReviewDeclineRepository.java ReviewDeclineRepository.java
git mv service/ReviewService.java ReviewService.java
git mv service/ReviewAssignmentService.java ReviewAssignmentService.java
git mv dto/PaperReviewView.java PaperReviewView.java
rmdir domain repository service dto
cd -
git mv src/main/java/org/confcms/cms/web/controller/ReviewRestController.java src/main/java/org/confcms/cms/review/ReviewRestController.java
```

- [ ] **Step 3: Move test files**

```bash
cd src/test/java/org/confcms/cms/review
git mv domain/ReviewTest.java ReviewTest.java
git mv service/ReviewAssignmentServiceTest.java ReviewAssignmentServiceTest.java
git mv service/ReviewServiceTest.java ReviewServiceTest.java
rmdir domain service
cd -
git mv src/test/java/org/confcms/cms/web/controller/ReviewRestControllerTest.java src/test/java/org/confcms/cms/review/ReviewRestControllerTest.java
```

- [ ] **Step 4: Update package declarations** in all 15 moved main files and 4 moved test files to `package org.confcms.cms.review;`. Remove every now-redundant same-package import among `Review`/`ReviewAssignment`/`ReviewBid`/`ReviewDecline`/`AssignmentStatus`/`BidType`/`ReviewDecision`/the 4 repositories/`ReviewService`/`ReviewAssignmentService`/`PaperReviewView`/`ReviewRestController` — this group cross-references itself heavily, so expect to delete many import lines here. `Paper`, `PaperVersion`, `User` references update to `org.confcms.cms.paper.*`/`org.confcms.cms.user.User`.

- [ ] **Step 5: Update every importer from Step 1** outside this group to `org.confcms.cms.review.*`.

- [ ] **Step 6: Compile and run the full suite**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, 273 tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: flatten review cluster into org.confcms.cms.review package"
```

---

### Task 12: `scheduling` package (flatten existing subfolders)

**Files to move** (within `src/main/java/org/confcms/cms/scheduling/`, flattening `domain/`, `repository/`, `service/`, `web/` into the package root):

- `scheduling/domain/Room.java` → `scheduling/Room.java`
- `scheduling/domain/Session.java` → `scheduling/Session.java`
- `scheduling/domain/Presentation.java` → `scheduling/Presentation.java`
- `scheduling/repository/RoomRepository.java` → `scheduling/RoomRepository.java`
- `scheduling/repository/SessionRepository.java` → `scheduling/SessionRepository.java`
- `scheduling/repository/PresentationRepository.java` → `scheduling/PresentationRepository.java`
- `scheduling/service/SchedulingService.java` → `scheduling/SchedulingService.java`
- `scheduling/web/SchedulingRestController.java` → `scheduling/SchedulingRestController.java`

(No test files found for this cluster in the current test listing — confirm via `find src/test/java/org/confcms/cms/scheduling -name "*.java"` before skipping; if any exist, move them the same way as other tasks.)

- [ ] **Step 1: Confirm whether any scheduling test files exist**

```bash
find src/test/java/org/confcms/cms/scheduling -name "*.java"
```

If this returns files, add moves for them in Step 3 following the same package-flattening pattern as the main files.

- [ ] **Step 2: Find every importer outside the `scheduling` package itself**

```bash
for sym in Room Session Presentation RoomRepository SessionRepository PresentationRepository SchedulingService SchedulingRestController; do
  echo "== $sym =="
  grep -rl "org\.confcms\.cms\.scheduling\.\(domain\|repository\|service\|web\)\.$sym;" --include="*.java" src/ | grep -v "^src/main/java/org/confcms/cms/scheduling/"
done
```

- [ ] **Step 3: Move main files**

```bash
cd src/main/java/org/confcms/cms/scheduling
git mv domain/Room.java Room.java
git mv domain/Session.java Session.java
git mv domain/Presentation.java Presentation.java
git mv repository/RoomRepository.java RoomRepository.java
git mv repository/SessionRepository.java SessionRepository.java
git mv repository/PresentationRepository.java PresentationRepository.java
git mv service/SchedulingService.java SchedulingService.java
git mv web/SchedulingRestController.java SchedulingRestController.java
rmdir domain repository service web
cd -
```

(Move any test files discovered in Step 1 here, same pattern.)

- [ ] **Step 4: Update package declarations** in all moved files to `package org.confcms.cms.scheduling;`. Remove now-redundant same-package imports among `Room`/`Session`/`Presentation`/their repositories/`SchedulingService`/`SchedulingRestController`. `Paper`, `Conference` references update to `org.confcms.cms.paper.Paper`/`org.confcms.cms.conference.Conference`.

- [ ] **Step 5: Update every importer from Step 2** outside this group to `org.confcms.cms.scheduling.*`.

- [ ] **Step 6: Compile and run the full suite**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, 273 tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: flatten scheduling cluster into org.confcms.cms.scheduling package"
```

---

### Task 13: `decision` package

**Files to move:**
- `src/main/java/org/confcms/cms/service/DecisionService.java` → `src/main/java/org/confcms/cms/decision/DecisionService.java`
- `src/main/java/org/confcms/cms/service/ProceedingsService.java` → `src/main/java/org/confcms/cms/decision/ProceedingsService.java`
- `src/main/java/org/confcms/cms/web/controller/AdminDecisionController.java` → `src/main/java/org/confcms/cms/decision/AdminDecisionController.java`
- `src/main/java/org/confcms/cms/web/controller/AdminDecisionViewController.java` → `src/main/java/org/confcms/cms/decision/AdminDecisionViewController.java`
- `src/main/java/org/confcms/cms/admin/controller/AdminController.java` → `src/main/java/org/confcms/cms/decision/AdminController.java`
- `src/test/java/org/confcms/cms/service/DecisionServiceTest.java` → `src/test/java/org/confcms/cms/decision/DecisionServiceTest.java`
- `src/test/java/org/confcms/cms/service/ProceedingsServiceTest.java` → `src/test/java/org/confcms/cms/decision/ProceedingsServiceTest.java`
- `src/test/java/org/confcms/cms/web/controller/AdminDecisionViewControllerTest.java` → `src/test/java/org/confcms/cms/decision/AdminDecisionViewControllerTest.java`
- `src/test/java/org/confcms/cms/admin/controller/AdminControllerTest.java` → `src/test/java/org/confcms/cms/decision/AdminControllerTest.java`

(No `AdminDecisionControllerTest` found in the current listing — per the deferred-findings tracking file's own M7 note, this gap is a known, pre-existing, plan-permitted omission, not something to fix as part of this move. Skip it.)

- [ ] **Step 1: Find every importer**

```bash
for sym in DecisionService ProceedingsService AdminDecisionController AdminDecisionViewController AdminController; do
  echo "== $sym =="
  grep -rl "org\.confcms\.cms\.\(service\|web\.controller\|admin\.controller\)\.$sym;" --include="*.java" src/
done
```

- [ ] **Step 2: Move main files**

```bash
mkdir -p src/main/java/org/confcms/cms/decision
git mv src/main/java/org/confcms/cms/service/DecisionService.java src/main/java/org/confcms/cms/decision/DecisionService.java
git mv src/main/java/org/confcms/cms/service/ProceedingsService.java src/main/java/org/confcms/cms/decision/ProceedingsService.java
git mv src/main/java/org/confcms/cms/web/controller/AdminDecisionController.java src/main/java/org/confcms/cms/decision/AdminDecisionController.java
git mv src/main/java/org/confcms/cms/web/controller/AdminDecisionViewController.java src/main/java/org/confcms/cms/decision/AdminDecisionViewController.java
git mv src/main/java/org/confcms/cms/admin/controller/AdminController.java src/main/java/org/confcms/cms/decision/AdminController.java
rmdir src/main/java/org/confcms/cms/admin/controller src/main/java/org/confcms/cms/admin 2>/dev/null || true
```

- [ ] **Step 3: Move test files**

```bash
mkdir -p src/test/java/org/confcms/cms/decision
git mv src/test/java/org/confcms/cms/service/DecisionServiceTest.java src/test/java/org/confcms/cms/decision/DecisionServiceTest.java
git mv src/test/java/org/confcms/cms/service/ProceedingsServiceTest.java src/test/java/org/confcms/cms/decision/ProceedingsServiceTest.java
git mv src/test/java/org/confcms/cms/web/controller/AdminDecisionViewControllerTest.java src/test/java/org/confcms/cms/decision/AdminDecisionViewControllerTest.java
git mv src/test/java/org/confcms/cms/admin/controller/AdminControllerTest.java src/test/java/org/confcms/cms/decision/AdminControllerTest.java
rmdir src/test/java/org/confcms/cms/admin/controller src/test/java/org/confcms/cms/admin 2>/dev/null || true
```

- [ ] **Step 4: Update package declarations** in all 5 moved main files and 4 moved test files to `package org.confcms.cms.decision;`. Remove now-redundant same-package imports (`AdminDecisionController`/`AdminDecisionViewController` importing `DecisionService`). `Paper`, `PaperVersion`, `Review`, `ReviewAssignment`, `Conference`, `User` references update to their respective new packages (`org.confcms.cms.paper.*`, `org.confcms.cms.review.*`, `org.confcms.cms.conference.Conference`, `org.confcms.cms.user.User`).

- [ ] **Step 5: Update every importer from Step 1** outside this group to `org.confcms.cms.decision.*`.

- [ ] **Step 6: Compile and run the full suite**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, 273 tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move decision cluster (DecisionService/ProceedingsService/AdminController) into org.confcms.cms.decision package"
```

---

### Task 14: `registration` package (flatten existing subfolders, absorb AdminRegistrationController)

**Files to move** (within `src/main/java/org/confcms/cms/registration/`, flattening `domain/`, `repository/`, `service/`, `web/controller/` into the package root; plus absorbing `AdminRegistrationController` from the top-level `web/controller`):

- `registration/domain/Registration.java` → `registration/Registration.java`
- `registration/domain/PaymentStatus.java` → `registration/PaymentStatus.java`
- `registration/repository/RegistrationRepository.java` → `registration/RegistrationRepository.java`
- `registration/service/RegistrationService.java` → `registration/RegistrationService.java`
- `registration/web/controller/RegistrationController.java` → `registration/RegistrationController.java`
- `web/controller/AdminRegistrationController.java` → `registration/AdminRegistrationController.java`

Test files, confirmed from the earlier listing:
- `registration/service/RegistrationServiceTest.java` → `registration/RegistrationServiceTest.java`
- `registration/web/controller/RegistrationControllerTest.java` → `registration/RegistrationControllerTest.java`
- `web/controller/AdminRegistrationControllerTest.java` → `registration/AdminRegistrationControllerTest.java`

This is the final task in the plan — after this one, the `web/controller/`, `web/exception/` (unchanged), `domain/`, `repository/`, `service/` top-level folders should contain ONLY the non-entity infra files listed in the design spec's "Unchanged" section, plus the three page-assembly controllers (`PublicWebController` stays in `publicweb/controller`, `DashboardController`/`AuthorDashboardController` stay in `web/controller`).

- [ ] **Step 1: Find every importer outside the `registration` package itself**

```bash
for sym in Registration PaymentStatus RegistrationRepository RegistrationService RegistrationController; do
  echo "== $sym =="
  grep -rl "org\.confcms\.cms\.registration\.\(domain\|repository\|service\|web\.controller\)\.$sym;" --include="*.java" src/ | grep -v "^src/main/java/org/confcms/cms/registration/" | grep -v "^src/test/java/org/confcms/cms/registration/"
done
grep -rl "org\.confcms\.cms\.web\.controller\.AdminRegistrationController;" --include="*.java" src/
```

- [ ] **Step 2: Move main files**

```bash
cd src/main/java/org/confcms/cms/registration
git mv domain/Registration.java Registration.java
git mv domain/PaymentStatus.java PaymentStatus.java
git mv repository/RegistrationRepository.java RegistrationRepository.java
git mv service/RegistrationService.java RegistrationService.java
git mv web/controller/RegistrationController.java RegistrationController.java
rmdir domain repository service web/controller web
cd -
git mv src/main/java/org/confcms/cms/web/controller/AdminRegistrationController.java src/main/java/org/confcms/cms/registration/AdminRegistrationController.java
```

- [ ] **Step 3: Move test files**

```bash
cd src/test/java/org/confcms/cms/registration
git mv service/RegistrationServiceTest.java RegistrationServiceTest.java
git mv web/controller/RegistrationControllerTest.java RegistrationControllerTest.java
rmdir service web/controller web
cd -
git mv src/test/java/org/confcms/cms/web/controller/AdminRegistrationControllerTest.java src/test/java/org/confcms/cms/registration/AdminRegistrationControllerTest.java
```

- [ ] **Step 4: Update package declarations** in all 6 moved main files and 3 moved test files to `package org.confcms.cms.registration;`. Remove now-redundant same-package imports (`RegistrationController`/`AdminRegistrationController`/`RegistrationService` importing `Registration`/`PaymentStatus`/`RegistrationRepository`). `User`, `Conference`, `FileStorageService` references update to `org.confcms.cms.user.User`, `org.confcms.cms.conference.Conference`, `org.confcms.cms.service.FileStorageService` (unchanged).

- [ ] **Step 5: Update every importer from Step 1** outside this group to `org.confcms.cms.registration.*`.

- [ ] **Step 6: Compile and run the full suite**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, 273 tests.

- [ ] **Step 7: Verify the top-level catch-all folders are now empty of entity-owned files**

```bash
ls src/main/java/org/confcms/cms/domain/ 2>/dev/null
ls src/main/java/org/confcms/cms/repository/ 2>/dev/null
ls src/main/java/org/confcms/cms/service/
ls src/main/java/org/confcms/cms/web/controller/
```

Expected: `domain/` and `repository/` no longer exist (or are empty — remove with `rmdir` if empty). `service/` contains only `EmailService.java`, `EmailQueueService.java`, `EmailMessage.java`, `EmailTemplateService.java`, `FileStorageService.java`, `GeoLocationService.java`. `web/controller/` contains only `DashboardController.java`, `AuthorDashboardController.java`.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: flatten registration cluster, absorb AdminRegistrationController, into org.confcms.cms.registration package"
```

---

### Task 15: Final verification and finish

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite one more time**

```bash
./gradlew test --rerun
```
Expected: `BUILD SUCCESSFUL`, 273 tests, 0 failures.

- [ ] **Step 2: Confirm no file anywhere still imports an old, now-nonexistent package path**

```bash
grep -rn "org\.confcms\.cms\.domain\." --include="*.java" src/ | grep -v "org.confcms.cms.domain;" || echo "clean"
grep -rln "org\.confcms\.cms\.repository\." --include="*.java" src/ || echo "clean"
grep -rln "org\.confcms\.cms\.submission\." --include="*.java" src/ || echo "clean"
grep -rln "org\.confcms\.cms\.auth\.service\." --include="*.java" src/ || echo "clean"
```

Each command should print `clean` or return nothing. Any match means a Step 5 ("update every importer") was missed in an earlier task — go back to that task and fix it.

- [ ] **Step 3: Hand off via finishing-a-development-branch**

Use the `superpowers:finishing-a-development-branch` skill to verify tests one more time and present the merge/PR/keep-as-is menu.
