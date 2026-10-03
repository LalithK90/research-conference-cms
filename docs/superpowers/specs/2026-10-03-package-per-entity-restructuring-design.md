# Package-per-Entity Restructuring Design

## Problem

The codebase is currently organized by layer-within-feature: each feature area (`submission`, `registration`, `review`, `scheduling`) has its own `domain/`, `repository/`, `service/`, `dto/` subfolders, and several top-level catch-alls (`domain/`, `repository/`, `service/`, `web/controller/`) hold everything that wasn't already split into a feature folder. Finding everything related to one entity means searching four or five different folders.

The user wants package-per-entity instead: for a given JPA entity, its entity class, repository, service, controller(s), and DTOs all live together in one flat package. This makes "show me everything about `User`" a single-folder answer.

## Scope

121 files under `src/main/java/org/confcms/cms`. Of these:
- **24 stay exactly where they are** — non-entity infrastructure (`config`, `security`, `core`, `web.config`, `web.exception`, generic `service` classes with no single entity owner: `EmailService`/`EmailQueueService`/`EmailMessage`/`EmailTemplateService`/`FileStorageService`/`GeoLocationService`, and the Spring Boot application class).
- **~97 move**, each into either a clean single-entity package or one of five agreed aggregate/cluster packages (below).
- **Page-assembly controllers stay put** too: `PublicWebController`, `DashboardController`, `AuthorDashboardController` read from several entities purely to render a page and don't own any entity's lifecycle — moving them would misrepresent what they do, same reasoning as the non-entity infra.

Test files under `src/test/java` mirror the same new package layout 1:1, same as they mirror the current layout today.

## Target Package Layout

All new packages sit directly under `org.confcms.cms` (replacing the current `domain`/`repository`/`service`/`web.controller` catch-alls for everything that moves). Each package is lowercase, named after its entity or its cluster.

### Clean single-entity packages

| Package | Contents (moved from) |
|---|---|
| `user` | `User.java` (domain), `UserRepository.java` (repository) |
| `useridentity` | `UserIdentity.java` (domain), `UserIdentityRepository.java` (repository) |
| `accesslog` | `AccessLog.java`, `AccessEventType.java` (domain), `AccessLogRepository.java` (repository), `AccessLogService.java` (service), `AdminAccessLogController.java` (web/controller) |
| `speaker` | `Speaker.java`, `SpeakerType.java` (domain), `SpeakerRepository.java` (repository), `AdminSpeakerController.java` (web/controller) |
| `sponsor` | `Sponsor.java`, `SponsorTier.java` (domain), `SponsorRepository.java` (repository), `AdminSponsorController.java` (web/controller) |
| `personinvitation` | `PersonInvitation.java`, `InvitationPurpose.java`, `InvitationStatus.java` (domain), `PersonInvitationRepository.java` (repository), `PersonInvitationService.java` (service), `PersonInvitationController.java`, `PersonInvitationAdminController.java` (web/controller) |
| `conflictdeclaration` | `ConflictDeclaration.java` (domain), `ConflictDeclarationRepository.java` (repository), `ConflictDeclarationService.java` (service), `ConflictDeclarationController.java` (web/controller) |
| `registration` | Unchanged location (already its own feature folder: `domain`, `repository`, `service`, `web/controller` → flattened into one `registration` package instead of four subfolders). Also absorbs `AdminRegistrationController.java` (currently in the top-level `web/controller`). |

### Aggregate/cluster packages (one package per workflow, multiple entities inside — same ownership reasoning as a single entity's package, just a wider aggregate)

| Package | Contents (moved from) |
|---|---|
| `paper` | `Paper.java`, `PaperAuthor.java`, `PaperVersion.java`, `PaperStatus.java`, `RevisionResolution.java` (submission/domain); `PaperRepository.java`, `PaperVersionRepository.java` (submission/repository); `SubmissionService.java` (submission/service); `AuthorRequestDto.java`, `ConferenceSummaryDto.java`, `PaperAuthorDto.java`, `PaperResponseDto.java`, `PaperVersionDto.java` (submission/dto); `SubmissionRestController.java` (web/controller) |
| `conference` | `Conference.java`, `ConferenceCommitteeRole.java`, `ConferencePaymentConfig.java`, `SubTheme.java`, `CommitteeRole.java`, `PaymentProvider.java`, `PaymentSecretConverter.java` (domain); `ConferenceRepository.java`, `ConferenceCommitteeRoleRepository.java`, `ConferencePaymentConfigRepository.java` (repository); `ConferenceService.java`, `CommitteeService.java` (service); `AdminConferenceController.java` (web/controller) |
| `auth` | `MagicLink.java`, `PasswordResetToken.java` (domain); `MagicLinkRepository.java`, `PasswordResetTokenRepository.java` (repository); `AuthService.java`, `MagicLinkService.java`, `PasswordResetService.java` (auth/service — folder already exists today, just loses its `service` subfolder level); `AuthRestController.java`, `PasswordResetController.java`, `AccountSettingsController.java`, `AdminUserController.java` (web/controller) |
| `review` | Unchanged location (already its own feature folder: `domain`, `repository`, `service`, `dto` → flattened into one `review` package). Also absorbs `ReviewRestController.java` (currently top-level `web/controller`). |
| `scheduling` | Unchanged location (already its own feature folder: `domain`, `repository`, `service`, `web` → flattened into one `scheduling` package). |
| `decision` | `DecisionService.java`, `ProceedingsService.java` (service); `AdminDecisionController.java`, `AdminDecisionViewController.java` (web/controller); `AdminController.java` (admin/controller) |

### Unchanged (non-entity infra and page-assembly controllers)

`config/`, `security/`, `core/domain/`, `core/security/`, `web/config/`, `web/exception/`, `service/{EmailService,EmailQueueService,EmailMessage,EmailTemplateService,FileStorageService,GeoLocationService}.java`, `web/controller/{PublicWebController,DashboardController,AuthorDashboardController}.java` (the last of these three is actually in `publicweb/controller` and `web/controller` respectively — kept as-is), `ConferenceCmsApplication.java`.

## Rationale for Aggregates vs. Strict 1:1

`paper`, `conference`, `review`, `scheduling`, and `decision` each bundle more than one entity. This is deliberate, not a compromise: in every one of these five cases, the "extra" entities (`PaperVersion`/`PaperAuthor`, `ConferenceCommitteeRole`/`ConferencePaymentConfig`/`SubTheme`, `ReviewAssignment`/`ReviewBid`/`ReviewDecline`, `Room`/`Session`/`Presentation`) have no independent service or controller today and are always read/written through the aggregate root's own service (`SubmissionService`, `AdminConferenceController`'s save flow, `ReviewAssignmentService`, `SchedulingService`). Splitting them into their own single-file packages would scatter a single cohesive workflow across 3-5 folders — the opposite of what this restructuring is for. The user confirmed this reasoning for the `conference` and `paper` clusters specifically during design; `review`/`scheduling` already exist as exactly this kind of cluster today and keep their existing internal cohesion; `decision` is newly named here to give `DecisionService`/`ProceedingsService`/the two admin decision controllers/`AdminController` a home, since none of those is itself an entity.

## Migration Mechanics

This is a mechanical package move at scale (121 files, ~97 moving), not a design change to any class's behavior. The approach:

1. **One commit per target package**, in dependency order (packages with fewer inbound references first, so each commit's "fix the imports that broke" step touches a smaller, more predictable set of files). Suggested order: `user` → `useridentity` → `accesslog` → `speaker` → `sponsor` → `personinvitation` → `conflictdeclaration` → `auth` → `conference` → `paper` → `review` → `scheduling` → `decision` → `registration`.
2. **Within each package's commit**: move the files (`git mv`, preserving history), update each moved file's own `package` declaration, then update every other file in the repo that imports a moved class.
3. **No behavior change.** Class names, method signatures, and field names are untouched — only the `package` line and the corresponding `import` lines elsewhere change.
4. **Full test suite run after every single package's commit**, not just at the end — a 97-file move is exactly the kind of change where a missed import reference fails silently until compile time, and catching it one package at a time keeps the failure surface small.
5. **Package-info or README update**: none planned; this spec is the record of the new layout.

## Risk and Verification

- **Compile-time safety net:** a missed import is a compile error, not a silent runtime bug — Java's compiler catches essentially 100% of mistakes in this kind of move. The real risk is volume (missing one of potentially dozens of import-site updates for a heavily-referenced class like `User` or `Paper`), not subtlety.
- **Test suite is the correctness check**: all 273 existing tests must continue passing after every package's move, since no behavior changes. A test failure after a move means an import was missed or a package-private/same-package access assumption broke (worth checking: do any moved classes rely on package-private visibility between classes that are about to end up in different packages? — flagged as a watch-item for the implementer, not confirmed as an issue).
- **IDE/tooling note**: this plan assumes a scripted `git mv` + sed-style import rewrite rather than relying on an IDE's "move package" refactor (no IDE is driving this session). The implementer should grep for every import of a moved class's fully-qualified name before considering that class's move complete.

## Out of Scope

- No change to any class's public API, method bodies, or field names.
- No change to test file *contents* beyond their `package` declaration and imports — assertions, mocks, and test logic are untouched.
- No change to `build.gradle`, resource files, or anything outside `src/main/java`/`src/test/java`.
- Page-assembly controllers and non-entity infrastructure are explicitly excluded, per the Scope section above.
