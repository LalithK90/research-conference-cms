# Submission REST Response DTO Design

## Problem

`SubmissionRestController` (`/submission/**`) has six endpoints that return a raw `Paper` JPA entity (or a `List<Paper>`) wrapped directly in `ResponseEntity.ok(...)`: `submitPaper`, `mySubmissions`, `uploadVersion`, `uploadRevision`, `uploadCameraReady`, `withdraw`.

Serializing `Paper` to JSON recurses through two independent bidirectional JPA relationships:

- `Paper.conference` (`Conference`) → `Conference.subThemes` (`List<SubTheme>`) → each `SubTheme.conference` points back to the same `Conference`, with no Jackson cycle-breaking annotation anywhere in the chain. Confirmed live: the response recurses until Jackson's default max-nesting-depth truncates it, producing a ~140KB response that never reaches `Paper`'s own top-level fields.
- `Paper.authors` (`List<PaperAuthor>`) → each `PaperAuthor.paper` points back to the same `Paper`. Confirmed live: a 34KB response visibly recursing `authors[].paper.authors[].paper...`.

Both predate the branches that found them and reproduce identically under Jackson 2 or Jackson 3 — this is a JPA/Jackson entity-graph problem, not a version-specific one. Originally filed as two separate deferred findings (roadmap #8 and the Spring Boot 4 migration), both explicitly noting they should be fixed together with a response DTO layer rather than patched with cycle-breaking annotations on the entities (which risks newly exposing `User.passwordHash` via `Paper.submitter.passwordHash` if the cycle is broken naively with `@JsonManagedReference`/`@JsonIgnore` alone).

This controller has no in-app caller — the HTML submission forms post to a separate author-dashboard controller path (`/author/submissions/...`), not this one. `/submission/**` exists solely for external, non-browser API clients. Changing its response shape is therefore safe to do without touching any HTML/JS consumer in this codebase, but it is a real break in whatever contract an external client already has with the current (broken) shape — acceptable here since the current shape is unusable JSON for any real client already.

## Approach

Introduce a response-DTO layer scoped to this controller, following the existing request-DTO convention already in `org.confcms.cms.submission.dto` (`AuthorRequestDto`). Each endpoint maps its `Paper`/`List<Paper>` result through a static factory before returning, rather than annotating the entity graph.

Rejected alternative: breaking the cycles in place with `@JsonIgnore`/`@JsonManagedReference`/`@JsonBackReference` on the entities. This is the exact approach the deferred findings flagged as risky (an incomplete annotation pass could newly serialize `passwordHash`), and it would still leak `PaperVersion.filePath` (an absolute server filesystem path) and show every `Conference`/`PaperVersion` field regardless of whether an external client should see it. The DTO approach has a clear allow-list instead of a forgotten-this-field deny-list.

## DTOs

All new, in `org.confcms.cms.submission.dto`:

### `PaperResponseDto`
- `id: Long`
- `title: String`
- `abstractText: String`
- `track: String`
- `status: String` (`PaperStatus.name()`)
- `conference: ConferenceSummaryDto`
- `versions: List<PaperVersionDto>`
- `authors: List<PaperAuthorDto>`
- Static `from(Paper paper)` and `from(List<Paper> papers)` factories.

### `ConferenceSummaryDto`
- `id: Long`
- `title: String`
- `startDate: LocalDate`
- `endDate: LocalDate`
- No `subThemes`, no back-reference of any kind — this is what breaks cycle #1. A client wanting sub-theme detail already has `/conference`-area endpoints for that (out of scope here).
- Static `from(Conference conference)`.

### `PaperVersionDto`
- `id: Long`
- `versionNumber: Integer`
- `originalFilename: String`
- `possibleDuplicate: boolean`
- `cameraReady: boolean`
- `copyrightTransferAgreedAt: Instant` (nullable) — the one piece of camera-ready confirmation data the submitting author would want back from this API.
- Excludes `filePath` (absolute server filesystem path — never client-facing), `plagiarismScore`, `plagiarismNote` (admin/committee-internal, entered via the admin UI, not this API's concern).
- Static `from(PaperVersion version)` and `from(List<PaperVersion> versions)`.

### `PaperAuthorDto`
- `id: Long`
- `fullName: String`
- `email: String`
- `affiliation: String`
- `presenter: boolean`
- No `paper` back-reference — this is what breaks cycle #2.
- Static `from(PaperAuthor author)` and `from(List<PaperAuthor> authors)`.

`Paper.submitter` (`User`, carrying `passwordHash`) is not represented anywhere in `PaperResponseDto` — the caller already knows who they are (it's their own paper, looked up by their own authenticated email), so there is no need to echo the submitter back at all.

## Controller Changes

`SubmissionRestController`'s six endpoints each wrap their existing `Paper`/`List<Paper>` result through the new factory before returning:

```java
Paper saved = submissionService.submitPaper(user, title, abstractText, track, file, authors);
return ResponseEntity.ok(PaperResponseDto.from(saved));
```

```java
return ResponseEntity.ok(PaperResponseDto.from(submissionService.getPapersBySubmitter(user)));
```

No change to request handling, authorization, status codes, or error-path bodies (`SecurityException`/`IllegalStateException` branches already return a plain string message, untouched).

## Testing

- Unit tests for each DTO's `from(...)` factory mapping the relevant fields correctly (one test per DTO is enough given these are pure data-mapping methods with no branching).
- Update the two existing `SubmissionRestControllerTest` assertions that currently check `response.getBody()).isEqualTo(saved)` (a raw `Paper`) to assert against the DTO's fields instead.
- New regression test: build a `Paper` with a populated `Conference` that has a `SubTheme` pointing back to it, and a `PaperAuthor` pointing back to the `Paper` — i.e., reconstruct both cycles that caused the original bug — then serialize `PaperResponseDto.from(paper)` through the same `JsonMapper` instance the controller already uses, and assert: (a) serialization completes without a stack overflow or truncated/oversized output, (b) the resulting JSON string does not contain `"passwordHash"` or `"filePath"` as a key, (c) the JSON round-trips to the expected flat shape (conference title, author list, version list all present and correctly sized).

## Out of Scope

- No change to `/author/submissions/...` (the HTML-form-facing controller) — it never returns JSON and is unaffected by this change.
- No change to any other controller that might return a raw entity (this spec is scoped to `SubmissionRestController` only, per the two originating findings).
- No versioning/back-compat shim for the old response shape — the current shape is unusable JSON for any real external client already (truncated recursive output), so there is no working contract to preserve.
