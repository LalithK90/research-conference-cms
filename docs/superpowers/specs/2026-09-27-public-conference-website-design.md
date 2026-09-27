# Public Conference Website — Design Spec

**Status:** Approved for implementation planning.

## 1. Problem

`research-conference-cms` needs a full public-facing marketing site — replacing an existing WordPress site (`ai-mert.ait.ac.th`, the AI-MERT conference, run by AIT) — so that each year's conference (About, Committee, Speakers, Sponsors, Call for Papers, Venue/Travel, Registration, Contact) is published from this app, using this app's existing multi-`Conference` data model, and matching the source site's branding.

Today, `PublicWebController` already routes `/about`, `/speakers`, `/schedule`, `/venue`, `/contact` to Thymeleaf templates that **do not exist** (`src/main/resources/templates/public/` only contains `home.html`, `register.html`, `committee.html`) — every one of those five routes currently 500s. `home.html` and `committee.html` are each fully self-contained HTML files (no shared layout/fragment), using a generic Bootstrap purple gradient hero, not AI-MERT's branding. There is no domain model for sponsors or speakers, no admin-editable free-text content fields on `Conference` (about text, call-for-papers text, venue/travel info), and no way to edit a `Conference` after creation (`AdminConferenceController` only has `/new` and `/save` — no edit endpoint).

## 2. Scope

**In scope:**
- A shared, branded Thymeleaf layout (nav, footer, color/font theme) used by every public page, replacing each page's current fully-duplicated HTML shell.
- New `Sponsor` and `Speaker` entities, scoped to a `Conference`, with simple admin CRUD (list/add/edit/delete), mirroring the existing `ConferenceCommitteeRole` management pattern.
- New free-text/structured content fields on `Conference`: `aboutHtml`, `callForPapersHtml`, `venueAddress`, `venueMapEmbedUrl`, `travelInfoHtml` — all nullable.
- A genuine **conference edit** action (`GET`/`POST /admin/conference/{id}/edit`), since the new content fields have no other way to be populated after a conference is created.
- Nine public pages, restyled/created: Home (restyle), About (new), Committee (restyle), Speakers (new), Sponsors (new), Call for Papers (new), Venue & Travel (new, combined), Registration (restyle), Contact (new), plus a Past Conferences archive (new) listing every non-active `Conference`.
- "Publish new conference year by year" mechanic: unchanged from the existing model — an admin creates a new `Conference` row (optionally cloning from a previous one, per roadmap item #13) and flips `isActive` to publish it; all public pages already read `${conference}` from `PublicWebController`'s `@ModelAttribution`-scoped active conference, so no new per-year publishing logic is needed beyond what already exists.
- Branding taken directly from the live source site's actual computed CSS (fetched and inspected, not guessed): primary `#06690f` (dark green), primary-hover/border `#05540c`, accent `#e91e63` (pink, used sparingly for secondary emphasis), body text `#64686d` (medium gray), footer background `#1f2024`, heading font `Montserrat`.

**Out of scope (explicitly deferred):**
- Any WordPress-style CMS editor (rich WYSIWYG). Admin content fields are plain `<textarea>` HTML/text inputs for this pass — an admin comfortable with basic HTML (or plain paragraphs) can use them; a proper rich-text editor is a future enhancement, not required to "complete" the site.
- Photo galleries, blog/news functionality, multi-language support — not present in the source site's core structure in a way that blocks launch, and not requested.
- Automated migration/import of the actual AI-MERT WordPress content (sponsor logos, speaker bios, past-conference history) into this app's database — this spec builds the *mechanism* to hold and display that content; populating it with AI-MERT's real 2024/2025/2026 data is a data-entry task for whoever administers the deployed instance, not a coding task.
- Any change to the actual submission/review/registration/payment backend logic — this spec is entirely about the public-facing presentation layer plus the minimal new content entities it needs.

## 3. Branding: colors, fonts, layout

Confirmed by fetching `https://ai-mert.ait.ac.th/` directly and grepping its inline `<style>` blocks for hex color literals (not a visual approximation):

| Token | Value | Usage on source site |
|---|---|---|
| `--brand-primary` | `#06690f` | Nav hover/active, links, primary button background, focus outline |
| `--brand-primary-dark` | `#05540c` | Primary button border, hover-darken state |
| `--brand-accent` | `#e91e63` | Sidebar widget headings, active pagination, secondary hover accents |
| `--brand-text` | `#64686d` | Body text, heading color |
| `--brand-footer-bg` | `#1f2024` | Footer background (white text on top) |
| `--brand-heading-font` | `Montserrat` | All heading elements (`h1`-`h6`), `font-weight: 600`, uppercase for nav-style headings |

These become CSS custom properties defined once in a new `src/main/resources/static/css/theme.css`, referenced by the shared layout fragment described in Section 4. This is a good-faith recreation of the source's palette and typography, not a pixel-perfect clone of its exact WordPress theme (Bizberg) — the source site's actual layout (multi-column mega-menu, sticky header, carousel hero) is generic WordPress-theme chrome, not something distinctive to preserve; only the *color identity* and *heading font* are being carried over, per the original request ("color template should be needed to taken from the above site").

## 4. Shared layout fragment

**New file:** `src/main/resources/templates/fragments/public_layout.html`

A Thymeleaf fragment defining:
- `<head>` block: theme CSS link, Google Fonts import for Montserrat, Bootstrap 5 (already used by existing pages, kept for grid/utility classes), viewport meta.
- Nav bar: brand logo/title (`${conference.title}`, unchanged pattern from today), links to Home / About / Committee / Speakers / Sponsors / Call for Papers / Venue & Travel / Registration / Past Conferences / Contact, plus the existing auth-aware Login/Dashboard/Logout block (copied verbatim from `home.html`'s existing `sec:authorize` blocks — no behavior change there).
- Footer: `#1f2024` background, `© <year> <conference.title>`, unchanged copyright pattern from today.

Every public template (`home.html`, `about.html`, `committee.html`, `speakers.html`, `sponsors.html`, `call_for_papers.html`, `venue.html`, `register.html`, `contact.html`, `past_conferences.html`) is restructured to `th:replace`/`th:insert` this fragment for its head/nav/footer, keeping only its own body content inline. This is the one real structural change needed — without it, the same nav/footer HTML would need to be hand-copied into 10 files and would drift out of sync (exactly the problem `admin/paper_detail.html`'s two independent bugs earlier this session came from: unshared, duplicated markup).

`home.html`'s existing hero gradient (`linear-gradient(135deg, #667eea 0%, #764ba2 100%)`) is replaced with a `--brand-primary` to `--brand-primary-dark` gradient, keeping the existing countdown-timer JS and CTA button structure exactly as-is (that logic is correct and unrelated to branding).

## 5. New domain model

### 5.1 `Sponsor` (new entity)

`src/main/java/org/confcms/cms/domain/Sponsor.java`, extends `BaseEntity`:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "conference_id", nullable = false)
private Conference conference;

@Column(nullable = false)
private String name;

@Column(nullable = false)
private String logoUrl;

private String websiteUrl; // nullable -- not every sponsor has a clickable logo

@Enumerated(EnumType.STRING)
@Column(nullable = false)
private SponsorTier tier = SponsorTier.PARTNER;

@Column(nullable = false)
private int displayOrder = 0;
```

`SponsorTier` enum: `PLATINUM, GOLD, SILVER, PARTNER`.

`SponsorRepository` (`src/main/java/org/confcms/cms/repository/SponsorRepository.java`):
```java
List<Sponsor> findByConferenceIdOrderByDisplayOrderAsc(Long conferenceId);
```

Tier ordering (`PLATINUM` before `GOLD` before `SILVER` before `PARTNER`) is NOT delegated to the repository: this field uses `EnumType.STRING` (matching every other enum in this codebase, e.g. `PaymentProvider`, `CommitteeRole`, for DB readability/portability), so a derived-query `OrderByTier` would sort alphabetically (`GOLD` < `PARTNER` < `PLATINUM` < `SILVER`), not by prominence. `PublicWebController.sponsors()` fetches the flat `displayOrder`-sorted list above, then groups it into a `Map<SponsorTier, List<Sponsor>>` in Java (iterating `SponsorTier.values()` for correct declaration-order tier grouping), and the template renders one section per tier in that order.

### 5.2 `Speaker` (new entity)

`src/main/java/org/confcms/cms/domain/Speaker.java`, extends `BaseEntity`:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "conference_id", nullable = false)
private Conference conference;

@Column(nullable = false)
private String fullName;

private String title; // e.g. "Professor, MIT" -- nullable, not every speaker has a public title line

@Column(columnDefinition = "TEXT")
private String bio;

private String photoUrl;

@Enumerated(EnumType.STRING)
@Column(nullable = false)
private SpeakerType type = SpeakerType.KEYNOTE;

@Column(nullable = false)
private int displayOrder = 0;
```

`SpeakerType` enum: `PLENARY, KEYNOTE` (matches the source site's exact nav split).

`SpeakerRepository` (`src/main/java/org/confcms/cms/repository/SpeakerRepository.java`):
```java
List<Speaker> findByConferenceIdOrderByDisplayOrderAsc(Long conferenceId);
```

### 5.3 `Conference` (extended, all new fields nullable)

Add to `src/main/java/org/confcms/cms/domain/Conference.java`:

```java
@Column(columnDefinition = "TEXT")
private String aboutHtml;

@Column(columnDefinition = "TEXT")
private String callForPapersHtml;

private String venueAddress;

private String venueMapEmbedUrl;

@Column(columnDefinition = "TEXT")
private String travelInfoHtml;
```

All five fields are nullable (no `@Column(nullable = false)`), following the pattern already established twice this session (`PaperVersion.plagiarismScore`/`plagiarismNote`, `PaperVersion.cameraReady`'s sibling `copyrightTransferAgreedAt`) specifically to avoid breaking `data-dev.sql`'s existing `INSERT INTO conferences` rows, which predate these columns. Confirm at implementation time via a dev-profile boot (per this session's established verification step) rather than assuming.

## 6. Admin CRUD

### 6.1 Conference edit (new gap this spec must close)

`AdminConferenceController` gains:
```java
@GetMapping("/{id}/edit")
public String editConferenceForm(@PathVariable Long id, Model model) { ... }

@PostMapping("/{id}/edit")
public String updateConference(@PathVariable Long id, @ModelAttribute ConferenceForm form) { ... }
```
Reuses the exact same `ConferenceForm` class already defined in this controller, extended with the five new fields from Section 5.3 (`aboutHtml`, `callForPapersHtml`, `venueAddress`, `venueMapEmbedUrl`, `travelInfoHtml`) as plain `<textarea>`/`<input>` fields — no rich-text editor (Section 2, out of scope). `editConferenceForm` loads the existing `Conference` by id and populates every form field including the new content fields; `updateConference` writes every field back onto the loaded entity and calls the existing `conferenceService.saveConference(conference)` (already handles the "only one active conference" invariant, unchanged).

The existing `admin/conference_form.html` gains a new "Content" section (About, Call for Papers, Venue & Travel fields) shown on both create and edit — since a fresh conference likely wants at least a placeholder for these fields before its public pages look reasonable, this section applies to `/new` too, not just `/edit`.

`admin/dashboard.html` (or wherever conferences are currently listed for an admin, if anywhere — verify at implementation time) gains an "Edit" link per conference row; if no such list exists yet, add a minimal one at `GET /admin/conference/list` reusing `conferenceRepository.findAll()`, since editing requires a way to find the id to edit.

### 6.2 Sponsor and Speaker admin CRUD

New `AdminSponsorController` (`/admin/conference/{conferenceId}/sponsors`) and `AdminSpeakerController` (`/admin/conference/{conferenceId}/speakers`), each with:
- `GET /` — list, `GET /new` — add form, `POST /save` — create, `GET /{id}/edit` — edit form, `POST /{id}/edit` — update, `POST /{id}/delete` — delete.
- `@PreAuthorize("hasRole('ADMIN')")` at the controller level, matching every other admin controller in this codebase.
- Both follow the exact CRUD shape already established by this codebase's `ConferenceCommitteeRoleRepository`-backed management (add/remove via `CommitteeService`) — no new architectural pattern, just two more entities following the one that already exists.

New templates: `admin/sponsors.html`, `admin/sponsor_form.html`, `admin/speakers.html`, `admin/speaker_form.html` — simple Bootstrap tables/forms matching the existing `admin/*.html` visual style (not the public branded theme — admin pages stay on the existing plain Bootstrap admin look, unchanged).

## 7. Public pages

All nine pages below use the shared layout fragment (Section 4) and read `${conference}` from `PublicWebController`'s existing `@ModelAttribute` (unchanged), except Past Conferences, which lists *all* conferences, not just the active one.

| Page | Route | Status | Content |
|---|---|---|---|
| Home | `/`, `/home` | Restyle | Existing hero/countdown/CTA structure, re-themed colors, nav updated to the new full link set |
| About | `/about` | New | Renders `${conference.aboutHtml}` as raw HTML (`th:utext`) inside a styled content container |
| Committee | `/committee` | Restyle | Existing `committeeService.getCommitteeForConference(...)` logic unchanged; only markup/branding changes |
| Speakers | `/speakers` | New | Two sections (Plenary, Keynote), each a card grid from `speakerRepository.findByConferenceIdOrderByDisplayOrderAsc(...)` filtered by `type` in the controller |
| Sponsors | `/sponsors` | New | Logo grid grouped by `SponsorTier`, sponsors within a tier in `displayOrder` |
| Call for Papers | `/call-for-papers` | New | Renders `${conference.callForPapersHtml}`, plus a "Submit Your Paper" button linking to the existing author-facing submission entry point (`/author/submissions` if logged in as an author, else `/login`) |
| Venue & Travel | `/venue` | New | `venueAddress`, an embedded `<iframe>` using `venueMapEmbedUrl` if present, and `${conference.travelInfoHtml}` for accommodation/transportation prose combined (per the approved "one combined page" scope decision) |
| Registration | `/register` | Restyle | Existing registration form/logic (in `RegistrationController`, unchanged) — only markup/branding changes |
| Contact | `/contact` | New | `${conference.contactEmail}`, plus any social links already on `Conference` (none exist today — a simple mailto link is sufficient, matching the "informational, not a new feature" scope) |
| Past Conferences | `/past-conferences` | New | Lists every `Conference` where `isActive == false`, ordered by `startDate` descending, each linking to a **read-only** detail view reusing the same About/Committee/Sponsors/Speakers templates but scoped to that specific (non-active) conference by id rather than the active one |

**Past Conferences detail routing:** `PublicWebController`'s existing `@ModelAttribute("conference")` method (`addConferenceToModel()`) takes no parameters and always resolves the *active* conference — it cannot be reused as-is for a specific past conference. Rather than duplicating templates, add separate explicit routes, one per section, that each look up the requested conference by id and add it to the model under the SAME attribute name (`"conference"`) the shared templates already expect, overriding what the `@ModelAttribute` method put there:

```java
@GetMapping("/past-conferences/{id}/about")
public String pastConferenceAbout(@PathVariable Long id, Model model) {
    model.addAttribute("conference", conferenceRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Conference not found")));
    return "public/about";
}
```

`PublicWebController` currently injects only `ConferenceService` (which exposes `getActiveConference()`, not a by-id lookup) — it does NOT currently inject `ConferenceRepository`. Add `ConferenceRepository` as a new constructor-injected field on this controller (Lombok `@RequiredArgsConstructor` already generates the constructor). No existing test file (`PublicWebControllerTest.java`) exists yet — confirmed via search — so Section 8's new test file for this controller starts fresh against the final constructor shape; there is no pre-existing test to migrate.

One such method per section (`about`, `committee`, `speakers`, `sponsors`) reusing the exact same view name as its active-conference counterpart — the template itself has no idea whether `${conference}` came from the `@ModelAttribute` method or an explicit override, so no template changes are needed for this to work. This is the same "one specific record by id, not the caller's own" shape already used by `AdminDecisionViewController.paperDetail(@PathVariable Long id, ...)`, just applied to public, unauthenticated routes and reusing existing public templates instead of new ones.

**Call for Papers / submission — explicit boundary (per direct instruction):** the source AI-MERT site has its own submission-guidelines and manuscript-upload pages; this app already has a complete, independent submission pipeline (`SubmissionRestController`, `SubmissionService`, the author-facing `/author/submissions` dashboard, and the full desk-review → assignment → decision → camera-ready → proceedings workflow built earlier in this project). The public Call for Papers page must NOT recreate any submission form, guidelines-as-a-form, or upload UI of its own — it is purely descriptive text (`${conference.callForPapersHtml}`) plus a single prominent link/button into this app's own existing entry point (`/author/submissions` if already authenticated as an author, `/login` otherwise, exactly as Section 7's table already specified). The end of the pipeline this button leads into is unchanged and already complete (submission → review → decision → camera-ready → proceedings, built across this project's prior roadmap items) — this spec adds no new submission-side functionality, only the public page that points at what already exists.

## 8. Testing

- Unit tests for `SponsorRepository`/`SpeakerRepository` query methods (standard `@DataJpaTest`-style, or Mockito-based controller tests reusing this codebase's existing pattern — verify at implementation time which style sibling repositories use).
- Controller tests for `AdminSponsorController`/`AdminSpeakerController` CRUD (create/edit/delete happy paths, matching `AdminConferenceControllerTest`'s existing Mockito style).
- Controller tests for `AdminConferenceController`'s new edit endpoints.
- Controller tests for `PublicWebController`'s new/changed routes (`/about`, `/speakers`, `/sponsors`, `/call-for-papers`, `/venue`, `/contact`, `/past-conferences`, and the past-conference-detail overloads) — verifying each returns 200 (not the current 500) and the correct view name/model attributes.
- Manual end-to-end verification (per this session's established pattern): boot dev profile with real seed data, walk every public page as an unauthenticated visitor confirming no 404/500, log in as admin and create a sponsor + speaker + edit a conference's content fields, confirm they render correctly on the corresponding public pages, confirm the past-conferences archive lists and correctly links to non-active seeded conferences.

## 9. Open questions resolved during brainstorming

- **Year-by-year publishing mechanic:** unchanged from the existing `Conference`/`isActive` model — no new publishing logic needed, only new pages/content fields to display what already exists per-year.
- **Sponsors/Speakers:** real entities with admin CRUD, not free-text blobs — matches this codebase's existing pattern for structured, repeatable per-conference data (e.g. `ConferenceCommitteeRole`, `SubTheme`).
- **Venue/Accommodation/Transportation:** one combined page with a few structured fields plus one free-text block, not three separate content sections — avoids building sparse admin UI for content that's realistically a few paragraphs per year.
- **Call for Papers:** purely informational, linking to the existing submission flow — no new submission logic.
- **Branding source:** the live site's actual inline CSS color literals (`#06690f`, `#05540c`, `#e91e63`, `#64686d`, `#1f2024`, Montserrat), confirmed by direct fetch and grep, not visual guesswork.
