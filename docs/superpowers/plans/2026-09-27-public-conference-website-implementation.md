# Public Conference Website Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a full public-facing marketing site for research-conference-cms (replacing AI-MERT's WordPress site), branded with AI-MERT's actual colors/fonts, supporting year-by-year publishing via the existing `Conference`/`isActive` model.

**Architecture:** A new shared Thymeleaf layout fragment carries branding across every public page. Two new entities (`Sponsor`, `Speaker`) with admin CRUD controllers/templates. Five new fields on `Conference` (about/call-for-papers/venue content) plus a new conference-edit action to populate them. Seven new public pages plus three restyles, all reading `${conference}` from the existing `PublicWebController` active-conference model attribute (unchanged), except a new Past Conferences archive that looks up a specific non-active conference by id.

**Tech Stack:** Spring Boot 3.5.8, Java 21, JPA/Hibernate, Thymeleaf, Bootstrap 5, JUnit 5 + Mockito + AssertJ.

**Spec:** `docs/superpowers/specs/2026-09-27-public-conference-website-design.md`

**Global Constraints:**
- All five new `Conference` fields (`aboutHtml`, `callForPapersHtml`, `venueAddress`, `venueMapEmbedUrl`, `travelInfoHtml`) must be nullable (no `@Column(nullable = false)`) — this codebase has twice broken `data-dev.sql`'s pre-existing seed INSERTs this session by adding a NOT NULL column to an existing entity; nullable-by-default avoids a third occurrence. Verify via a dev-profile boot after Task 1 rather than assuming.
- Do NOT wire up the existing but currently-unused Trumbowyg rich-text editor (`src/main/resources/templates/fragments/trumbowygScript.html`/`trumbowygStyle.html`, `static/dist/trumbowyg*`) for the new content fields — confirmed dead/unreferenced scaffolding, out of scope per the spec's explicit "plain textarea, no rich-text editor" decision. Plain `<textarea>` inputs are correct here.
- `PublicWebController` currently injects only `ConferenceService` (no `ConferenceRepository`). Adding `ConferenceRepository` as a new constructor field changes the Lombok-generated constructor's argument list — no existing test file for this controller exists yet (confirmed via search), so this is a clean addition, not a migration.
- Every new public template must use the shared layout fragment from Task 2 — no new template should duplicate the nav/footer/head HTML inline (that duplication is exactly what caused two independent, undetected bugs in `admin/paper_detail.html` earlier this session).
- Run `./gradlew test` after every task; the suite must stay green (currently 220 tests, 0 failures) before moving to the next task.
- Admin pages (sponsor/speaker CRUD, conference edit) use the EXISTING plain-Bootstrap admin look (dark navbar, `btn-outline-light` nav links, matching `admin/dashboard.html`/`admin/registrations.html`) — NOT the new public branded theme. Only the nine public-facing pages get AI-MERT branding.

---

### Task 1: Domain model — `Sponsor`, `Speaker`, `Conference` new fields

**Files:**
- Create: `src/main/java/org/confcms/cms/domain/Sponsor.java`
- Create: `src/main/java/org/confcms/cms/domain/SponsorTier.java`
- Create: `src/main/java/org/confcms/cms/domain/Speaker.java`
- Create: `src/main/java/org/confcms/cms/domain/SpeakerType.java`
- Create: `src/main/java/org/confcms/cms/repository/SponsorRepository.java`
- Create: `src/main/java/org/confcms/cms/repository/SpeakerRepository.java`
- Modify: `src/main/java/org/confcms/cms/domain/Conference.java`

- [ ] **Step 1: Create `SponsorTier` enum**

```java
package org.confcms.cms.domain;

public enum SponsorTier {
    PLATINUM,
    GOLD,
    SILVER,
    PARTNER
}
```

- [ ] **Step 2: Create `Sponsor` entity**

```java
package org.confcms.cms.domain;

import org.confcms.cms.core.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "sponsors")
@Getter
@Setter
public class Sponsor extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String logoUrl;

    private String websiteUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SponsorTier tier = SponsorTier.PARTNER;

    @Column(nullable = false)
    private int displayOrder = 0;
}
```

- [ ] **Step 3: Create `SpeakerType` enum**

```java
package org.confcms.cms.domain;

public enum SpeakerType {
    PLENARY,
    KEYNOTE
}
```

- [ ] **Step 4: Create `Speaker` entity**

```java
package org.confcms.cms.domain;

import org.confcms.cms.core.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "speakers")
@Getter
@Setter
public class Speaker extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @Column(nullable = false)
    private String fullName;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String bio;

    private String photoUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SpeakerType type = SpeakerType.KEYNOTE;

    @Column(nullable = false)
    private int displayOrder = 0;
}
```

- [ ] **Step 5: Create `SponsorRepository`**

```java
package org.confcms.cms.repository;

import org.confcms.cms.domain.Sponsor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SponsorRepository extends JpaRepository<Sponsor, Long> {
    List<Sponsor> findByConferenceIdOrderByDisplayOrderAsc(Long conferenceId);
}
```

- [ ] **Step 6: Create `SpeakerRepository`**

```java
package org.confcms.cms.repository;

import org.confcms.cms.domain.Speaker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SpeakerRepository extends JpaRepository<Speaker, Long> {
    List<Speaker> findByConferenceIdOrderByDisplayOrderAsc(Long conferenceId);
}
```

- [ ] **Step 7: Add the five new nullable fields to `Conference`**

Edit `src/main/java/org/confcms/cms/domain/Conference.java` — add these fields immediately after the existing `contactEmail` field, before the `@OneToMany` collections:

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

None of these five fields have `@Column(nullable = false)` — this is deliberate (see Global Constraints).

- [ ] **Step 8: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Boot the dev profile to confirm no seed-data breakage**

Run: `SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun > /tmp/public-site-task1-boot.log 2>&1 &` then poll the log (`grep -q "Started ConferenceCmsApplication" /tmp/public-site-task1-boot.log`) for up to 60 seconds.
Expected: `Started ConferenceCmsApplication` appears, no `ERROR`/`Exception`/`APPLICATION FAILED` lines. This confirms the new tables (`sponsors`, `speakers`) and the five new nullable `Conference` columns don't break `data-dev.sql`'s existing `INSERT INTO conferences` rows.
Then stop the app: find the PID via `lsof -i :8080 -sTCP:LISTEN -t` and `kill -9` it; confirm the port is free.

- [ ] **Step 10: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 220 tests passing (no new tests yet — this task only touches domain model).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/org/confcms/cms/domain/Sponsor.java src/main/java/org/confcms/cms/domain/SponsorTier.java src/main/java/org/confcms/cms/domain/Speaker.java src/main/java/org/confcms/cms/domain/SpeakerType.java src/main/java/org/confcms/cms/repository/SponsorRepository.java src/main/java/org/confcms/cms/repository/SpeakerRepository.java src/main/java/org/confcms/cms/domain/Conference.java
git commit -m "feat: add Sponsor/Speaker entities and public-content fields on Conference"
```

---

### Task 2: Shared branded layout fragment + theme CSS

**Files:**
- Create: `src/main/resources/static/css/theme.css`
- Create: `src/main/resources/templates/fragments/public_layout.html`

- [ ] **Step 1: Create the theme stylesheet**

Create `src/main/resources/static/css/theme.css`:

```css
/* AI-MERT-derived brand theme -- colors and heading font taken directly from
   ai-mert.ait.ac.th's own inline CSS (fetched and inspected, not guessed). */
:root {
    --brand-primary: #06690f;
    --brand-primary-dark: #05540c;
    --brand-accent: #e91e63;
    --brand-text: #64686d;
    --brand-footer-bg: #1f2024;
    --brand-heading-font: 'Montserrat', sans-serif;
}

body {
    color: var(--brand-text);
}

h1, h2, h3, h4, h5, h6 {
    font-family: var(--brand-heading-font);
    font-weight: 600;
}

.navbar-brand {
    font-family: var(--brand-heading-font);
    font-weight: bold;
    font-size: 1.4rem;
}

.public-navbar {
    background-color: #ffffff !important;
    border-bottom: 3px solid var(--brand-primary);
}

.public-navbar .nav-link {
    color: var(--brand-text) !important;
}

.public-navbar .nav-link:hover,
.public-navbar .nav-link.active {
    color: var(--brand-primary) !important;
}

.btn-brand-primary {
    background-color: var(--brand-primary);
    border-color: var(--brand-primary-dark);
    color: #ffffff;
}

.btn-brand-primary:hover {
    background-color: var(--brand-primary-dark);
    border-color: var(--brand-primary-dark);
    color: #ffffff;
}

.public-hero {
    background: linear-gradient(135deg, var(--brand-primary) 0%, var(--brand-primary-dark) 100%);
    color: #ffffff;
    padding: 100px 0;
}

.public-footer {
    background-color: var(--brand-footer-bg);
    color: #ffffff;
}

.public-footer a {
    color: #ffffff;
}

.sponsor-tier-heading {
    color: var(--brand-accent);
    text-transform: uppercase;
    font-size: 1rem;
    letter-spacing: 1px;
}
```

- [ ] **Step 2: Create the shared layout fragment**

Create `src/main/resources/templates/fragments/public_layout.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:sec="http://www.thymeleaf.org/extras/spring-security">

<head th:fragment="head(pageTitle)">
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title th:text="${pageTitle} + ' - ' + ${conference != null ? conference.title : 'Conference'}">Conference</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link href="https://fonts.googleapis.com/css2?family=Montserrat:wght@400;600;700&display=swap" rel="stylesheet">
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/app.css" rel="stylesheet">
    <link href="/css/theme.css" rel="stylesheet">
</head>

<body>
    <nav th:fragment="nav" class="navbar navbar-expand-lg public-navbar">
        <div class="container">
            <a class="navbar-brand" href="/" th:text="${conference != null ? conference.title : 'Conference'}">Conference</a>
            <button class="navbar-toggler" type="button" data-bs-toggle="collapse" data-bs-target="#navbarNav">
                <span class="navbar-toggler-icon"></span>
            </button>
            <div class="collapse navbar-collapse" id="navbarNav">
                <ul class="navbar-nav ms-auto">
                    <li class="nav-item"><a class="nav-link" href="/">Home</a></li>
                    <li class="nav-item"><a class="nav-link" href="/about">About</a></li>
                    <li class="nav-item"><a class="nav-link" href="/committee">Committee</a></li>
                    <li class="nav-item"><a class="nav-link" href="/speakers">Speakers</a></li>
                    <li class="nav-item"><a class="nav-link" href="/sponsors">Sponsors</a></li>
                    <li class="nav-item"><a class="nav-link" href="/call-for-papers">Call for Papers</a></li>
                    <li class="nav-item"><a class="nav-link" href="/venue">Venue &amp; Travel</a></li>
                    <li class="nav-item"><a class="nav-link" href="/register">Registration</a></li>
                    <li class="nav-item"><a class="nav-link" href="/past-conferences">Past Conferences</a></li>
                    <li class="nav-item"><a class="nav-link" href="/contact">Contact</a></li>
                    <li class="nav-item" sec:authorize="isAuthenticated()">
                        <a class="nav-link" href="/dashboard">Dashboard</a>
                    </li>
                    <li class="nav-item" sec:authorize="!isAuthenticated()">
                        <a class="nav-link" href="/login">Login</a>
                    </li>
                    <li class="nav-item" sec:authorize="isAuthenticated()">
                        <form method="post" th:action="@{/logout}" class="d-inline">
                            <button type="submit" class="btn btn-link nav-link" style="border:none;">Logout</button>
                        </form>
                    </li>
                </ul>
            </div>
        </div>
    </nav>

    <footer th:fragment="footer" class="public-footer text-center py-4">
        <p>&copy; <span th:text="${#calendars.format(#calendars.createNow(), 'yyyy')}">2026</span>
            <span th:text="${conference != null ? conference.title : 'Conference'}">Conference</span>. All rights reserved.</p>
    </footer>

    <script th:fragment="scripts" src="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/js/bootstrap.bundle.min.js"></script>
</body>

</html>
```

Note: this file is never rendered directly as a page — it exists only to hold `th:fragment` blocks (`head(pageTitle)`, `nav`, `footer`, `scripts`) that other templates pull in via `th:replace`/`th:insert` in later tasks.

- [ ] **Step 3: Compile and run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 220 tests passing (no Java changes in this task, template/CSS only).

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/css/theme.css src/main/resources/templates/fragments/public_layout.html
git commit -m "feat: add shared branded layout fragment and theme CSS"
```

---

### Task 3: Restyle existing public pages to use the shared layout

**Files:**
- Modify: `src/main/resources/templates/public/home.html`
- Modify: `src/main/resources/templates/public/committee.html`
- Modify: `src/main/resources/templates/public/register.html`

- [ ] **Step 1: Rewrite `home.html` to use the shared fragment**

Replace the full contents of `src/main/resources/templates/public/home.html` with:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:sec="http://www.thymeleaf.org/extras/spring-security">

<head th:replace="~{fragments/public_layout :: head('Home')}"></head>

<body>
    <nav th:replace="~{fragments/public_layout :: nav}"></nav>

    <div class="public-hero text-center">
        <div class="container">
            <h1 class="display-3" th:text="${conference.title}">Conference Title</h1>
            <p class="lead" th:text="${conference.venue}">Venue</p>
            <p class="lead" th:text="${conference.startDate} + ' - ' + ${conference.endDate}">Dates</p>
            <div th:if="${conference != null}" id="countdown" class="mt-3" th:attr="data-end-date=${conference.endDate}">
                <div class="h5">Time until conference end:</div>
                <div id="countdown-timer" class="h3 fw-bold">--:--:--</div>
            </div>
            <a href="/register" class="btn btn-light btn-lg mt-3">Register Now</a>
        </div>
    </div>

    <div class="container my-5">
        <div class="row">
            <div class="col-md-4 text-center">
                <h3>Call for Papers</h3>
                <p>Submit your research and contribute to advancing knowledge</p>
                <a href="/call-for-papers" class="btn btn-brand-primary">Learn More</a>
            </div>
            <div class="col-md-4 text-center">
                <h3>Keynote Speakers</h3>
                <p>Learn from leading experts in the field</p>
                <a href="/speakers" class="btn btn-brand-primary">View Speakers</a>
            </div>
            <div class="col-md-4 text-center">
                <h3>Sponsors</h3>
                <p>Meet the organizations supporting this conference</p>
                <a href="/sponsors" class="btn btn-brand-primary">View Sponsors</a>
            </div>
        </div>
    </div>

    <footer th:replace="~{fragments/public_layout :: footer}"></footer>

    <script th:replace="~{fragments/public_layout :: scripts}"></script>
    <script>
        // Simple countdown to conference end date (reads YYYY-MM-DD from data attribute)
        (function(){
            const el = document.getElementById('countdown');
            if (!el) return;
            const endDate = el.dataset.endDate; // e.g. 2025-11-30
            if (!endDate) return;
            const target = new Date(endDate + 'T23:59:59');
            const timer = document.getElementById('countdown-timer');
            function update() {
                const now = new Date();
                const diff = target - now;
                if (diff <= 0) {
                    timer.textContent = 'Conference ended';
                    clearInterval(interval);
                    return;
                }
                const days = Math.floor(diff / (1000*60*60*24));
                const hours = Math.floor((diff % (1000*60*60*24)) / (1000*60*60));
                const mins = Math.floor((diff % (1000*60*60)) / (1000*60));
                const secs = Math.floor((diff % (1000*60)) / 1000);
                timer.textContent = days + 'd ' + String(hours).padStart(2,'0') + ':' + String(mins).padStart(2,'0') + ':' + String(secs).padStart(2,'0');
            }
            update();
            const interval = setInterval(update, 1000);
        })();
    </script>
</body>

</html>
```

Note: the "Submit Paper" card that linked to `/author/submit` is replaced with a "Call for Papers" card linking to `/call-for-papers` (Task 6's new page) — `/author/submit` was never a real route in this app (the actual submission entry point is the author dashboard at `/author/submissions`, added earlier this session); the new Call for Papers page is the correct public-facing link target per the spec.

- [ ] **Step 2: Rewrite `committee.html` to use the shared fragment**

Replace the full contents of `src/main/resources/templates/public/committee.html` with:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head th:replace="~{fragments/public_layout :: head('Committee')}"></head>

<body>
    <nav th:replace="~{fragments/public_layout :: nav}"></nav>

    <div class="container my-5">
        <h1 class="text-center mb-5">Committee</h1>

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

    <footer th:replace="~{fragments/public_layout :: footer}"></footer>
    <script th:replace="~{fragments/public_layout :: scripts}"></script>
</body>

</html>
```

- [ ] **Step 3: Rewrite `register.html` to use the shared fragment**

Replace the full contents of `src/main/resources/templates/public/register.html` with:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head th:replace="~{fragments/public_layout :: head('Registration')}"></head>

<body>
    <nav th:replace="~{fragments/public_layout :: nav}"></nav>

    <div class="container mt-5 mb-5">
        <div class="row justify-content-center">
            <div class="col-md-8">
                <div class="card shadow">
                    <div class="card-header" style="background-color: var(--brand-primary); color: white;">
                        <h3 class="mb-0">Conference Registration</h3>
                    </div>
                    <div class="card-body">
                        <div th:if="${user == null}" class="alert alert-warning">
                            Please <a href="/login">login</a> or <a href="/login">create an account</a> to register.
                        </div>

                        <div th:if="${user != null}">
                            <h5 th:text="${conference.title}">Conference Title</h5>
                            <p class="text-muted" th:text="${conference.venue} + ' | ' + ${conference.startDate}">Venue
                                | Date</p>
                            <hr>

                            <div th:if="${error}" class="alert alert-danger" th:text="${error}"></div>

                            <form th:action="@{/register}" method="post" enctype="multipart/form-data">
                                <div class="mb-3">
                                    <label class="form-label">Select Ticket Type</label>
                                    <select name="ticketType" class="form-select" required onchange="updatePrice(this)">
                                        <option value="REGULAR" data-price="100">Regular Attendee ($100)</option>
                                        <option value="STUDENT" data-price="50">Student ($50)</option>
                                        <option value="VIRTUAL" data-price="25">Virtual Access ($25)</option>
                                    </select>
                                </div>

                                <input type="hidden" name="amount" id="amountInput" value="100">

                                <div class="alert alert-info" th:if="${paymentConfig != null}">
                                    <h6 class="alert-heading">Payment Method: <span
                                            th:text="${paymentConfig.provider}">PROVIDER</span></h6>

                                    <div th:if="${paymentConfig.provider.name() == 'LOCAL_BANK'}">
                                        <p class="mb-0">Please transfer the fees to the following account:</p>
                                        <pre class="mt-2 bg-light p-2 border" th:text="${paymentConfig.bankDetails}">
                                        </pre>
                                        <small>Your registration will be pending until payment is verified.</small>
                                        <div class="mb-3 mt-2">
                                            <label class="form-label">Upload Bank Slip (PDF, JPEG, or PNG)</label>
                                            <input type="file" class="form-control" name="bankSlip" accept=".pdf,.jpg,.jpeg,.png">
                                        </div>
                                    </div>

                                    <div th:if="${paymentConfig.provider.name() == 'STRIPE'}">
                                        <p>You will be redirected to Stripe for secure payment.</p>
                                        <div id="card-element" class="form-control mb-3"></div>
                                    </div>

                                    <div th:if="${paymentConfig.provider.name() == 'PAYPAL'}">
                                        <p>Pay securely with PayPal.</p>
                                    </div>

                                    <div th:if="${paymentConfig.provider.name() == 'FREE'}">
                                        <p>This conference is free to attend.</p>
                                    </div>
                                </div>

                                <div class="d-grid">
                                    <button type="submit" class="btn btn-brand-primary btn-lg">Complete Registration</button>
                                </div>
                            </form>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <footer th:replace="~{fragments/public_layout :: footer}"></footer>
    <script th:replace="~{fragments/public_layout :: scripts}"></script>
    <script>
        function updatePrice (select) {
            const price = select.options[select.selectedIndex].getAttribute('data-price');
            document.getElementById('amountInput').value = price;
        }
    </script>
</body>

</html>
```

Note: this file's controller logic (`RegistrationController`) is completely unchanged — only markup/branding changed. Do not touch `src/main/java/org/confcms/cms/registration/web/controller/RegistrationController.java` in this task.

- [ ] **Step 4: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 220 tests passing (template-only changes, no new Java tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/public/home.html src/main/resources/templates/public/committee.html src/main/resources/templates/public/register.html
git commit -m "feat: restyle home, committee, and registration pages with brand theme"
```

---

### Task 4: `PublicWebController` — new dependencies and simple new routes

**Files:**
- Modify: `src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java`
- Create: `src/main/resources/templates/public/about.html`
- Create: `src/main/resources/templates/public/contact.html`
- Test: Create `src/test/java/org/confcms/cms/publicweb/controller/PublicWebControllerTest.java`

- [ ] **Step 1: Confirm no existing test file**

Run: `find src/test -iname "PublicWebControllerTest.java"`
Expected: no output (confirmed absent during plan-writing; verify it's still true before creating a new one).

- [ ] **Step 2: Add `ConferenceRepository` and `SponsorRepository`/`SpeakerRepository` dependencies, implement `about` and `contact`**

Edit `src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java`. Add imports:

```java
import org.confcms.cms.repository.ConferenceRepository;
```

Add the new field (Lombok `@RequiredArgsConstructor` regenerates the constructor):

```java
    private final ConferenceRepository conferenceRepository;
```

Replace the existing stub `about` method:

```java
    @GetMapping("/about")
    public String about(Model model) {
        return "public/about";
    }
```

with (unchanged signature/body — `${conference}` from the existing `@ModelAttribute` already carries `aboutHtml`, no new model attribute needed):

```java
    @GetMapping("/about")
    public String about(Model model) {
        return "public/about";
    }
```

(No code change needed here beyond what already exists — the template itself reads `${conference.aboutHtml}` directly. This step exists only to confirm the method already compiles correctly against the new `Conference` field; no edit required if the method body is already this simple.)

Replace the existing stub `contact` method similarly — no code change needed, same reasoning (the template reads `${conference.contactEmail}` directly, already available).

- [ ] **Step 3: Create `about.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head th:replace="~{fragments/public_layout :: head('About')}"></head>

<body>
    <nav th:replace="~{fragments/public_layout :: nav}"></nav>

    <div class="container my-5">
        <h1 class="text-center mb-5" th:text="'About ' + (${conference != null} ? ${conference.title} : '')">About</h1>
        <div class="row justify-content-center">
            <div class="col-md-10">
                <div th:if="${conference == null or conference.aboutHtml == null}" class="alert alert-info">
                    About information for this conference has not been published yet.
                </div>
                <div th:if="${conference != null and conference.aboutHtml != null}" th:utext="${conference.aboutHtml}"></div>
            </div>
        </div>
    </div>

    <footer th:replace="~{fragments/public_layout :: footer}"></footer>
    <script th:replace="~{fragments/public_layout :: scripts}"></script>
</body>

</html>
```

- [ ] **Step 4: Create `contact.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head th:replace="~{fragments/public_layout :: head('Contact')}"></head>

<body>
    <nav th:replace="~{fragments/public_layout :: nav}"></nav>

    <div class="container my-5">
        <h1 class="text-center mb-5">Contact Us</h1>
        <div class="row justify-content-center">
            <div class="col-md-6 text-center">
                <div th:if="${conference != null and conference.contactEmail != null}">
                    <p>For questions about this conference, please contact:</p>
                    <p>
                        <a th:href="'mailto:' + ${conference.contactEmail}" th:text="${conference.contactEmail}"
                           class="btn btn-brand-primary">Email Us</a>
                    </p>
                </div>
                <div th:if="${conference == null or conference.contactEmail == null}" class="alert alert-info">
                    Contact information for this conference has not been published yet.
                </div>
            </div>
        </div>
    </div>

    <footer th:replace="~{fragments/public_layout :: footer}"></footer>
    <script th:replace="~{fragments/public_layout :: scripts}"></script>
</body>

</html>
```

- [ ] **Step 5: Write controller tests**

Create `src/test/java/org/confcms/cms/publicweb/controller/PublicWebControllerTest.java`:

```java
package org.confcms.cms.publicweb.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.repository.ConferenceRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.ConferenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class PublicWebControllerTest {

    @Mock
    private ConferenceService conferenceService;
    @Mock
    private CommitteeService committeeService;
    @Mock
    private ClientRegistrationRepository clientRegistrationRepository;
    @Mock
    private ConferenceRepository conferenceRepository;

    private PublicWebController controller() {
        return new PublicWebController(conferenceService, committeeService, clientRegistrationRepository, conferenceRepository);
    }

    @Test
    void aboutReturnsAboutView() {
        Model model = new ExtendedModelMap();
        String view = controller().about(model);
        assertThat(view).isEqualTo("public/about");
    }

    @Test
    void contactReturnsContactView() {
        Model model = new ExtendedModelMap();
        String view = controller().contact(model);
        assertThat(view).isEqualTo("public/contact");
    }
}
```

Confirm the constructor argument order in the test matches `PublicWebController`'s actual field declaration order (Lombok `@RequiredArgsConstructor` follows declaration order exactly) — read the file after Step 2's edit to verify `conferenceRepository` was added last, matching this test's constructor call.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests "*PublicWebControllerTest*"`
Expected: PASS, 2 tests green.

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 222 tests passing.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java src/main/resources/templates/public/about.html src/main/resources/templates/public/contact.html src/test/java/org/confcms/cms/publicweb/controller/PublicWebControllerTest.java
git commit -m "feat: add About and Contact public pages"
```

---

### Task 5: Speakers and Sponsors public pages

**Files:**
- Modify: `src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java`
- Create: `src/main/resources/templates/public/speakers.html`
- Create: `src/main/resources/templates/public/sponsors.html`
- Test: `src/test/java/org/confcms/cms/publicweb/controller/PublicWebControllerTest.java`

- [ ] **Step 1: Add `SpeakerRepository`/`SponsorRepository` dependencies and the two new controller methods**

Edit `src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java`. Add imports:

```java
import org.confcms.cms.domain.Speaker;
import org.confcms.cms.domain.SpeakerType;
import org.confcms.cms.domain.Sponsor;
import org.confcms.cms.domain.SponsorTier;
import org.confcms.cms.repository.SpeakerRepository;
import org.confcms.cms.repository.SponsorRepository;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
```

Add two new constructor-injected fields (after `conferenceRepository` from Task 4):

```java
    private final SpeakerRepository speakerRepository;
    private final SponsorRepository sponsorRepository;
```

Replace the existing stub `speakers` method:

```java
    @GetMapping("/speakers")
    public String speakers(Model model) {
        Conference activeConference = conferenceService.getActiveConference();
        List<Speaker> speakers = speakerRepository.findByConferenceIdOrderByDisplayOrderAsc(activeConference.getId());
        model.addAttribute("plenarySpeakers", speakers.stream()
                .filter(s -> s.getType() == SpeakerType.PLENARY).toList());
        model.addAttribute("keynoteSpeakers", speakers.stream()
                .filter(s -> s.getType() == SpeakerType.KEYNOTE).toList());
        return "public/speakers";
    }
```

Add a new `sponsors` method (there is no existing stub for this route today — it's a genuinely new page/route, not in the original nav):

```java
    @GetMapping("/sponsors")
    public String sponsors(Model model) {
        Conference activeConference = conferenceService.getActiveConference();
        List<Sponsor> sponsors = sponsorRepository.findByConferenceIdOrderByDisplayOrderAsc(activeConference.getId());
        Map<SponsorTier, List<Sponsor>> byTier = new EnumMap<>(SponsorTier.class);
        for (SponsorTier tier : SponsorTier.values()) {
            byTier.put(tier, sponsors.stream().filter(s -> s.getTier() == tier).toList());
        }
        model.addAttribute("sponsorsByTier", byTier);
        return "public/sponsors";
    }
```

`EnumMap` iterates in enum declaration order (`PLATINUM, GOLD, SILVER, PARTNER`), so the template's `th:each` over this map renders tiers in prominence order without any extra sorting logic — this is the mechanism the spec's Section 5.1 describes for correct tier grouping.

- [ ] **Step 2: Create `speakers.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head th:replace="~{fragments/public_layout :: head('Speakers')}"></head>

<body>
    <nav th:replace="~{fragments/public_layout :: nav}"></nav>

    <div class="container my-5">
        <h1 class="text-center mb-5">Speakers</h1>

        <h2 class="mb-4">Plenary Speakers</h2>
        <div th:if="${#lists.isEmpty(plenarySpeakers)}" class="alert alert-info">
            Plenary speakers will be announced soon.
        </div>
        <div class="row mb-5">
            <div class="col-md-4 mb-4" th:each="speaker : ${plenarySpeakers}">
                <div class="card h-100 text-center">
                    <img th:if="${speaker.photoUrl}" th:src="${speaker.photoUrl}" alt="Photo" class="rounded-circle mx-auto mt-3"
                         style="width: 120px; height: 120px; object-fit: cover;">
                    <div class="card-body">
                        <h5 class="card-title" th:text="${speaker.fullName}">Name</h5>
                        <p class="text-muted" th:if="${speaker.title}" th:text="${speaker.title}">Title</p>
                        <p th:if="${speaker.bio}" th:text="${speaker.bio}">Bio</p>
                    </div>
                </div>
            </div>
        </div>

        <h2 class="mb-4">Keynote Speakers</h2>
        <div th:if="${#lists.isEmpty(keynoteSpeakers)}" class="alert alert-info">
            Keynote speakers will be announced soon.
        </div>
        <div class="row">
            <div class="col-md-4 mb-4" th:each="speaker : ${keynoteSpeakers}">
                <div class="card h-100 text-center">
                    <img th:if="${speaker.photoUrl}" th:src="${speaker.photoUrl}" alt="Photo" class="rounded-circle mx-auto mt-3"
                         style="width: 120px; height: 120px; object-fit: cover;">
                    <div class="card-body">
                        <h5 class="card-title" th:text="${speaker.fullName}">Name</h5>
                        <p class="text-muted" th:if="${speaker.title}" th:text="${speaker.title}">Title</p>
                        <p th:if="${speaker.bio}" th:text="${speaker.bio}">Bio</p>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <footer th:replace="~{fragments/public_layout :: footer}"></footer>
    <script th:replace="~{fragments/public_layout :: scripts}"></script>
</body>

</html>
```

- [ ] **Step 3: Create `sponsors.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head th:replace="~{fragments/public_layout :: head('Sponsors')}"></head>

<body>
    <nav th:replace="~{fragments/public_layout :: nav}"></nav>

    <div class="container my-5">
        <h1 class="text-center mb-5">Sponsors</h1>

        <div th:each="entry : ${sponsorsByTier}" class="mb-5">
            <div th:if="${not #lists.isEmpty(entry.value)}">
                <h3 class="sponsor-tier-heading mb-4" th:text="${entry.key}">TIER</h3>
                <div class="row align-items-center">
                    <div class="col-md-3 col-6 mb-4 text-center" th:each="sponsor : ${entry.value}">
                        <a th:if="${sponsor.websiteUrl}" th:href="${sponsor.websiteUrl}" target="_blank" rel="noopener">
                            <img th:src="${sponsor.logoUrl}" th:alt="${sponsor.name}" class="img-fluid" style="max-height: 100px;">
                        </a>
                        <img th:unless="${sponsor.websiteUrl}" th:src="${sponsor.logoUrl}" th:alt="${sponsor.name}"
                             class="img-fluid" style="max-height: 100px;">
                    </div>
                </div>
            </div>
        </div>

        <div th:if="${sponsorsByTier.values().?[!isEmpty(#this)].isEmpty()}" class="alert alert-info text-center">
            Sponsors for this conference will be announced soon.
        </div>
    </div>

    <footer th:replace="~{fragments/public_layout :: footer}"></footer>
    <script th:replace="~{fragments/public_layout :: scripts}"></script>
</body>

</html>
```

- [ ] **Step 4: Write controller tests**

Add to `src/test/java/org/confcms/cms/publicweb/controller/PublicWebControllerTest.java` — update the `controller()` helper's constructor call to the new 6-argument constructor:

```java
    @Mock
    private org.confcms.cms.repository.SpeakerRepository speakerRepository;
    @Mock
    private org.confcms.cms.repository.SponsorRepository sponsorRepository;

    private PublicWebController controller() {
        return new PublicWebController(conferenceService, committeeService, clientRegistrationRepository,
                conferenceRepository, speakerRepository, sponsorRepository);
    }
```

Add these tests:

```java
    @Test
    void speakersSplitsIntoPlenaryAndKeynoteLists() {
        org.confcms.cms.domain.Conference conference = new org.confcms.cms.domain.Conference();
        conference.setId(1L);
        org.mockito.Mockito.when(conferenceService.getActiveConference()).thenReturn(conference);

        org.confcms.cms.domain.Speaker plenary = new org.confcms.cms.domain.Speaker();
        plenary.setType(org.confcms.cms.domain.SpeakerType.PLENARY);
        org.confcms.cms.domain.Speaker keynote = new org.confcms.cms.domain.Speaker();
        keynote.setType(org.confcms.cms.domain.SpeakerType.KEYNOTE);
        org.mockito.Mockito.when(speakerRepository.findByConferenceIdOrderByDisplayOrderAsc(1L))
                .thenReturn(java.util.List.of(plenary, keynote));

        Model model = new ExtendedModelMap();
        String view = controller().speakers(model);

        assertThat(view).isEqualTo("public/speakers");
        assertThat((java.util.List<?>) model.getAttribute("plenarySpeakers")).containsExactly(plenary);
        assertThat((java.util.List<?>) model.getAttribute("keynoteSpeakers")).containsExactly(keynote);
    }

    @Test
    void sponsorsGroupsByTierInDeclarationOrder() {
        org.confcms.cms.domain.Conference conference = new org.confcms.cms.domain.Conference();
        conference.setId(1L);
        org.mockito.Mockito.when(conferenceService.getActiveConference()).thenReturn(conference);

        org.confcms.cms.domain.Sponsor gold = new org.confcms.cms.domain.Sponsor();
        gold.setTier(org.confcms.cms.domain.SponsorTier.GOLD);
        org.confcms.cms.domain.Sponsor platinum = new org.confcms.cms.domain.Sponsor();
        platinum.setTier(org.confcms.cms.domain.SponsorTier.PLATINUM);
        org.mockito.Mockito.when(sponsorRepository.findByConferenceIdOrderByDisplayOrderAsc(1L))
                .thenReturn(java.util.List.of(gold, platinum));

        Model model = new ExtendedModelMap();
        String view = controller().sponsors(model);

        assertThat(view).isEqualTo("public/sponsors");
        @SuppressWarnings("unchecked")
        java.util.Map<org.confcms.cms.domain.SponsorTier, java.util.List<org.confcms.cms.domain.Sponsor>> byTier =
                (java.util.Map<org.confcms.cms.domain.SponsorTier, java.util.List<org.confcms.cms.domain.Sponsor>>) model.getAttribute("sponsorsByTier");
        assertThat(byTier.get(org.confcms.cms.domain.SponsorTier.PLATINUM)).containsExactly(platinum);
        assertThat(byTier.get(org.confcms.cms.domain.SponsorTier.GOLD)).containsExactly(gold);
        assertThat(byTier.get(org.confcms.cms.domain.SponsorTier.SILVER)).isEmpty();
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "*PublicWebControllerTest*"`
Expected: PASS, 4 tests green (2 from Task 4 + 2 new).

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 224 tests passing.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java src/main/resources/templates/public/speakers.html src/main/resources/templates/public/sponsors.html src/test/java/org/confcms/cms/publicweb/controller/PublicWebControllerTest.java
git commit -m "feat: add Speakers and Sponsors public pages"
```

---

### Task 6: Call for Papers and Venue & Travel public pages

**Files:**
- Modify: `src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java`
- Create: `src/main/resources/templates/public/call_for_papers.html`
- Create: `src/main/resources/templates/public/venue.html`
- Test: `src/test/java/org/confcms/cms/publicweb/controller/PublicWebControllerTest.java`

- [ ] **Step 1: Add the two new controller methods**

Edit `src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java`. The existing `venue` method is a stub already present:

```java
    @GetMapping("/venue")
    public String venue(Model model) {
        return "public/venue";
    }
```

No code change needed (the template reads `${conference.venueAddress}`/`venueMapEmbedUrl`/`travelInfoHtml` directly). Confirm this method still exists unchanged.

Add a new `callForPapers` method (there is no existing stub for this route — it's genuinely new, not in the original nav which only had `/schedule`):

```java
    @GetMapping("/call-for-papers")
    public String callForPapers(Model model) {
        return "public/call_for_papers";
    }
```

Remove the now-unused `/schedule` stub method (`schedule(Model model)` returning `"public/schedule"`) — the spec's nav (Section 4) does not include a Schedule link, and no `schedule.html` template exists or is planned; leaving this dead route in place would keep a 500-on-visit link if anything still references `/schedule`. Confirm nothing in the templates being edited in this plan links to `/schedule` before removing it (the shared layout fragment from Task 2 already does not include it).

- [ ] **Step 2: Create `call_for_papers.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" xmlns:sec="http://www.thymeleaf.org/extras/spring-security">

<head th:replace="~{fragments/public_layout :: head('Call for Papers')}"></head>

<body>
    <nav th:replace="~{fragments/public_layout :: nav}"></nav>

    <div class="container my-5">
        <h1 class="text-center mb-4">Call for Papers</h1>
        <div class="row justify-content-center mb-4">
            <div class="col-md-8 text-center">
                <a sec:authorize="isAuthenticated()" href="/author/submissions" class="btn btn-brand-primary btn-lg">Go to My Submissions</a>
                <a sec:authorize="!isAuthenticated()" href="/login" class="btn btn-brand-primary btn-lg">Login to Submit a Paper</a>
            </div>
        </div>
        <div class="row justify-content-center">
            <div class="col-md-10">
                <div th:if="${conference == null or conference.callForPapersHtml == null}" class="alert alert-info">
                    Call for papers details for this conference have not been published yet.
                </div>
                <div th:if="${conference != null and conference.callForPapersHtml != null}" th:utext="${conference.callForPapersHtml}"></div>
            </div>
        </div>
    </div>

    <footer th:replace="~{fragments/public_layout :: footer}"></footer>
    <script th:replace="~{fragments/public_layout :: scripts}"></script>
</body>

</html>
```

This page is purely descriptive plus a link into the existing, already-complete submission pipeline — it does not implement any submission form, guideline-entry UI, or upload logic of its own (per the spec's explicit boundary in Section 7).

- [ ] **Step 3: Create `venue.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head th:replace="~{fragments/public_layout :: head('Venue & Travel')}"></head>

<body>
    <nav th:replace="~{fragments/public_layout :: nav}"></nav>

    <div class="container my-5">
        <h1 class="text-center mb-5">Venue &amp; Travel</h1>

        <div class="row justify-content-center mb-4" th:if="${conference != null and conference.venueAddress != null}">
            <div class="col-md-8">
                <h4>Venue Address</h4>
                <p th:text="${conference.venueAddress}">Address</p>
            </div>
        </div>

        <div class="row justify-content-center mb-4" th:if="${conference != null and conference.venueMapEmbedUrl != null}">
            <div class="col-md-8">
                <div class="ratio ratio-16x9">
                    <iframe th:src="${conference.venueMapEmbedUrl}" allowfullscreen loading="lazy"></iframe>
                </div>
            </div>
        </div>

        <div class="row justify-content-center">
            <div class="col-md-8">
                <div th:if="${conference == null or conference.travelInfoHtml == null}" class="alert alert-info">
                    Venue, accommodation, and transportation details for this conference have not been published yet.
                </div>
                <div th:if="${conference != null and conference.travelInfoHtml != null}" th:utext="${conference.travelInfoHtml}"></div>
            </div>
        </div>
    </div>

    <footer th:replace="~{fragments/public_layout :: footer}"></footer>
    <script th:replace="~{fragments/public_layout :: scripts}"></script>
</body>

</html>
```

- [ ] **Step 4: Write controller tests**

Add to `src/test/java/org/confcms/cms/publicweb/controller/PublicWebControllerTest.java`:

```java
    @Test
    void callForPapersReturnsCallForPapersView() {
        Model model = new ExtendedModelMap();
        String view = controller().callForPapers(model);
        assertThat(view).isEqualTo("public/call_for_papers");
    }

    @Test
    void venueReturnsVenueView() {
        Model model = new ExtendedModelMap();
        String view = controller().venue(model);
        assertThat(view).isEqualTo("public/venue");
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "*PublicWebControllerTest*"`
Expected: PASS, 6 tests green.

- [ ] **Step 6: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 226 tests passing.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java src/main/resources/templates/public/call_for_papers.html src/main/resources/templates/public/venue.html src/test/java/org/confcms/cms/publicweb/controller/PublicWebControllerTest.java
git commit -m "feat: add Call for Papers and Venue & Travel public pages"
```

---

### Task 7: Past Conferences archive

**Files:**
- Modify: `src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java`
- Create: `src/main/resources/templates/public/past_conferences.html`
- Test: `src/test/java/org/confcms/cms/publicweb/controller/PublicWebControllerTest.java`

- [ ] **Step 1: Add the archive-list route and four per-id detail overloads**

Edit `src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java`. Add imports:

```java
import java.util.Comparator;
```

Add these five new methods (place after `venue`):

```java
    @GetMapping("/past-conferences")
    public String pastConferences(Model model) {
        List<Conference> past = conferenceRepository.findAll().stream()
                .filter(c -> !c.isActive())
                .sorted(Comparator.comparing(Conference::getStartDate).reversed())
                .toList();
        model.addAttribute("pastConferences", past);
        return "public/past_conferences";
    }

    @GetMapping("/past-conferences/{id}/about")
    public String pastConferenceAbout(@PathVariable Long id, Model model) {
        model.addAttribute("conference", loadConferenceById(id));
        return "public/about";
    }

    @GetMapping("/past-conferences/{id}/committee")
    public String pastConferenceCommittee(@PathVariable Long id, Model model) {
        Conference target = loadConferenceById(id);
        model.addAttribute("conference", target);
        model.addAttribute("committee", committeeService.getCommitteeForConference(target));
        return "public/committee";
    }

    @GetMapping("/past-conferences/{id}/speakers")
    public String pastConferenceSpeakers(@PathVariable Long id, Model model) {
        Conference target = loadConferenceById(id);
        model.addAttribute("conference", target);
        List<Speaker> speakers = speakerRepository.findByConferenceIdOrderByDisplayOrderAsc(target.getId());
        model.addAttribute("plenarySpeakers", speakers.stream()
                .filter(s -> s.getType() == SpeakerType.PLENARY).toList());
        model.addAttribute("keynoteSpeakers", speakers.stream()
                .filter(s -> s.getType() == SpeakerType.KEYNOTE).toList());
        return "public/speakers";
    }

    @GetMapping("/past-conferences/{id}/sponsors")
    public String pastConferenceSponsors(@PathVariable Long id, Model model) {
        Conference target = loadConferenceById(id);
        model.addAttribute("conference", target);
        List<Sponsor> sponsors = sponsorRepository.findByConferenceIdOrderByDisplayOrderAsc(target.getId());
        Map<SponsorTier, List<Sponsor>> byTier = new EnumMap<>(SponsorTier.class);
        for (SponsorTier tier : SponsorTier.values()) {
            byTier.put(tier, sponsors.stream().filter(s -> s.getTier() == tier).toList());
        }
        model.addAttribute("sponsorsByTier", byTier);
        return "public/sponsors";
    }

    private Conference loadConferenceById(Long id) {
        return conferenceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));
    }
```

Add `@PathVariable` to the existing imports if not already present:

```java
import org.springframework.web.bind.annotation.PathVariable;
```

Note: `model.addAttribute("conference", ...)` inside these methods overrides whatever the class-level `@ModelAttribute("conference")` method already put in the model for this request — Spring MVC applies `@ModelAttribute` methods before the handler method runs, and a later explicit `model.addAttribute` with the same name replaces it. This is exactly the mechanism the spec's Section 7 describes; no template changes are needed since `about.html`/`committee.html`/`speakers.html`/`sponsors.html` already read `${conference}` generically.

- [ ] **Step 2: Create `past_conferences.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head th:replace="~{fragments/public_layout :: head('Past Conferences')}"></head>

<body>
    <nav th:replace="~{fragments/public_layout :: nav}"></nav>

    <div class="container my-5">
        <h1 class="text-center mb-5">Past Conferences</h1>

        <div th:if="${#lists.isEmpty(pastConferences)}" class="alert alert-info text-center">
            No past conferences to show yet.
        </div>

        <div class="row">
            <div class="col-md-6 mb-4" th:each="past : ${pastConferences}">
                <div class="card h-100">
                    <div class="card-body">
                        <h5 class="card-title" th:text="${past.title}">Title</h5>
                        <p class="text-muted" th:text="${past.venue} + ' | ' + ${past.startDate} + ' - ' + ${past.endDate}">Venue | Dates</p>
                        <div class="d-flex gap-2 flex-wrap">
                            <a th:href="@{'/past-conferences/' + ${past.id} + '/about'}" class="btn btn-sm btn-outline-secondary">About</a>
                            <a th:href="@{'/past-conferences/' + ${past.id} + '/committee'}" class="btn btn-sm btn-outline-secondary">Committee</a>
                            <a th:href="@{'/past-conferences/' + ${past.id} + '/speakers'}" class="btn btn-sm btn-outline-secondary">Speakers</a>
                            <a th:href="@{'/past-conferences/' + ${past.id} + '/sponsors'}" class="btn btn-sm btn-outline-secondary">Sponsors</a>
                        </div>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <footer th:replace="~{fragments/public_layout :: footer}"></footer>
    <script th:replace="~{fragments/public_layout :: scripts}"></script>
</body>

</html>
```

- [ ] **Step 3: Write controller tests**

Add to `src/test/java/org/confcms/cms/publicweb/controller/PublicWebControllerTest.java`:

```java
    @Test
    void pastConferencesListsOnlyInactiveConferencesNewestFirst() {
        org.confcms.cms.domain.Conference active = new org.confcms.cms.domain.Conference();
        active.setActive(true);
        active.setStartDate(java.time.LocalDate.of(2026, 1, 1));

        org.confcms.cms.domain.Conference older = new org.confcms.cms.domain.Conference();
        older.setActive(false);
        older.setStartDate(java.time.LocalDate.of(2024, 1, 1));

        org.confcms.cms.domain.Conference newer = new org.confcms.cms.domain.Conference();
        newer.setActive(false);
        newer.setStartDate(java.time.LocalDate.of(2025, 1, 1));

        org.mockito.Mockito.when(conferenceRepository.findAll()).thenReturn(java.util.List.of(active, older, newer));

        Model model = new ExtendedModelMap();
        String view = controller().pastConferences(model);

        assertThat(view).isEqualTo("public/past_conferences");
        assertThat((java.util.List<?>) model.getAttribute("pastConferences")).containsExactly(newer, older);
    }

    @Test
    void pastConferenceAboutLoadsTheRequestedConferenceNotTheActiveOne() {
        org.confcms.cms.domain.Conference target = new org.confcms.cms.domain.Conference();
        target.setId(42L);
        target.setTitle("2024 Edition");
        org.mockito.Mockito.when(conferenceRepository.findById(42L)).thenReturn(java.util.Optional.of(target));

        Model model = new ExtendedModelMap();
        String view = controller().pastConferenceAbout(42L, model);

        assertThat(view).isEqualTo("public/about");
        assertThat(model.getAttribute("conference")).isEqualTo(target);
        org.mockito.Mockito.verify(conferenceService, org.mockito.Mockito.never()).getActiveConference();
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "*PublicWebControllerTest*"`
Expected: PASS, 8 tests green.

- [ ] **Step 5: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 228 tests passing.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/confcms/cms/publicweb/controller/PublicWebController.java src/main/resources/templates/public/past_conferences.html src/test/java/org/confcms/cms/publicweb/controller/PublicWebControllerTest.java
git commit -m "feat: add Past Conferences archive with per-year detail views"
```

---

### Task 8: Conference edit action + admin form content section

**Files:**
- Modify: `src/main/java/org/confcms/cms/web/controller/AdminConferenceController.java`
- Modify: `src/main/resources/templates/admin/conference_form.html`
- Modify: `src/main/resources/templates/admin/dashboard.html`
- Test: `src/test/java/org/confcms/cms/web/controller/AdminConferenceControllerTest.java`

- [ ] **Step 1: Add the five new content fields to `ConferenceForm`**

Edit `src/main/java/org/confcms/cms/web/controller/AdminConferenceController.java` — add to the `ConferenceForm` inner class, after `cloneFromConferenceId`:

```java
        // Public-site content (Task 1's new Conference fields)
        private String aboutHtml;
        private String callForPapersHtml;
        private String venueAddress;
        private String venueMapEmbedUrl;
        private String travelInfoHtml;
```

- [ ] **Step 2: Populate these fields in `saveConference` and add edit endpoints**

In `saveConference`, immediately after `conference.setContactEmail(form.getContactEmail());`, add:

```java
        conference.setAboutHtml(form.getAboutHtml());
        conference.setCallForPapersHtml(form.getCallForPapersHtml());
        conference.setVenueAddress(form.getVenueAddress());
        conference.setVenueMapEmbedUrl(form.getVenueMapEmbedUrl());
        conference.setTravelInfoHtml(form.getTravelInfoHtml());
```

Also populate these same five fields in `buildFormFromClonedConference`, immediately after `form.setContactEmail(source.getContactEmail());`, so cloning also carries over content text as a starting point (consistent with the rest of that method's existing prefill behavior):

```java
        form.setAboutHtml(source.getAboutHtml());
        form.setCallForPapersHtml(source.getCallForPapersHtml());
        form.setVenueAddress(source.getVenueAddress());
        form.setVenueMapEmbedUrl(source.getVenueMapEmbedUrl());
        form.setTravelInfoHtml(source.getTravelInfoHtml());
```

Add two new endpoints, after `saveConference`:

```java
    @GetMapping("/list")
    public String listConferences(Model model) {
        model.addAttribute("conferences", conferenceRepository.findAll());
        return "admin/conference_list";
    }

    @GetMapping("/{id}/edit")
    public String editConferenceForm(@PathVariable Long id, Model model) {
        Conference conference = conferenceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));

        ConferenceForm form = new ConferenceForm();
        form.setTitle(conference.getTitle());
        form.setVenue(conference.getVenue());
        form.setStartDate(conference.getStartDate());
        form.setEndDate(conference.getEndDate());
        form.setActive(conference.isActive());
        form.setBlindReview(conference.isBlindReview());
        form.setLogoUrl(conference.getLogoUrl());
        form.setContactEmail(conference.getContactEmail());
        form.setAboutHtml(conference.getAboutHtml());
        form.setCallForPapersHtml(conference.getCallForPapersHtml());
        form.setVenueAddress(conference.getVenueAddress());
        form.setVenueMapEmbedUrl(conference.getVenueMapEmbedUrl());
        form.setTravelInfoHtml(conference.getTravelInfoHtml());

        ConferenceCommitteeRole chairRole = committeeService.getCommitteeForConference(conference).stream()
                .filter(r -> r.getRole() == CommitteeRole.CHAIR)
                .findFirst().orElse(null);
        if (chairRole != null) {
            form.setChairUserId(chairRole.getUser().getId());
        }
        for (ConferenceCommitteeRole role : committeeService.getCommitteeForConference(conference)) {
            if (role.getRole() == CommitteeRole.CO_CHAIR) {
                form.getCoChairUserIds().add(role.getUser().getId());
            }
        }

        ConferencePaymentConfig paymentConfig = conference.getPaymentConfig();
        if (paymentConfig != null) {
            form.setPaymentProvider(paymentConfig.getProvider());
            form.setStripePublishableKey(paymentConfig.getStripePublishableKey());
            form.setStripeSecretKey(paymentConfig.getStripeSecretKey());
            form.setPaypalClientId(paymentConfig.getPaypalClientId());
            form.setPaypalClientSecret(paymentConfig.getPaypalClientSecret());
            form.setBankDetails(paymentConfig.getBankDetails());
        }

        model.addAttribute("conferenceForm", form);
        model.addAttribute("allUsers", userRepository.findAll());
        model.addAttribute("allConferences", conferenceRepository.findAll());
        model.addAttribute("editingConferenceId", id);
        return "admin/conference_form";
    }

    @PostMapping("/{id}/edit")
    public String updateConference(@PathVariable Long id, @ModelAttribute ConferenceForm form) {
        Conference conference = conferenceRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));

        conference.setTitle(form.getTitle());
        conference.setVenue(form.getVenue());
        conference.setStartDate(form.getStartDate());
        conference.setEndDate(form.getEndDate());
        conference.setActive(form.isActive());
        conference.setBlindReview(form.isBlindReview());
        conference.setLogoUrl(form.getLogoUrl());
        conference.setContactEmail(form.getContactEmail());
        conference.setAboutHtml(form.getAboutHtml());
        conference.setCallForPapersHtml(form.getCallForPapersHtml());
        conference.setVenueAddress(form.getVenueAddress());
        conference.setVenueMapEmbedUrl(form.getVenueMapEmbedUrl());
        conference.setTravelInfoHtml(form.getTravelInfoHtml());

        ConferencePaymentConfig paymentConfig = conference.getPaymentConfig();
        if (paymentConfig == null) {
            paymentConfig = new ConferencePaymentConfig();
            paymentConfig.setConference(conference);
            conference.setPaymentConfig(paymentConfig);
        }
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

        conferenceService.saveConference(conference);
        return "redirect:/admin/conference/list";
    }
```

Add `import org.springframework.web.bind.annotation.PathVariable;` if not already present.

Note: `updateConference` deliberately does NOT touch chair/co-chair committee roles — editing committee membership after creation is a separate concern (already handled by whatever mechanism manages `ConferenceCommitteeRole` day-to-day, unrelated to this spec) and is out of scope here; the edit form still displays the current chair/co-chairs (read-only context via the pre-filled `chairUserId`/`coChairUserIds`) but `updateConference` ignores those two fields on submit, matching the principle "don't add functionality beyond what the task requires." If reviewers flag this as confusing (a field shown but not saved), the fix is to make those two `<select>` fields `disabled` in the edit form specifically — decide this at implementation/review time based on how confusing the rendered form actually looks.

- [ ] **Step 3: Add the "Content" section to `conference_form.html`**

Edit `src/main/resources/templates/admin/conference_form.html` — add a new section immediately before the closing `<div class="d-grid gap-2">` submit-button block:

```html
                    <hr>

                    <!-- Public Site Content -->
                    <h5 class="mb-3">Public Site Content</h5>
                    <p class="text-muted small">Plain HTML/text is supported; a rich-text editor is not part of this
                        first version. Leave blank to show a "not yet published" placeholder on the public site.</p>

                    <div class="mb-3">
                        <label class="form-label">About This Conference</label>
                        <textarea class="form-control" rows="5" th:field="*{aboutHtml}"></textarea>
                    </div>

                    <div class="mb-3">
                        <label class="form-label">Call for Papers</label>
                        <textarea class="form-control" rows="5" th:field="*{callForPapersHtml}"></textarea>
                    </div>

                    <div class="row mb-3">
                        <div class="col-md-6">
                            <label class="form-label">Venue Address</label>
                            <input type="text" class="form-control" th:field="*{venueAddress}">
                        </div>
                        <div class="col-md-6">
                            <label class="form-label">Venue Map Embed URL</label>
                            <input type="text" class="form-control" th:field="*{venueMapEmbedUrl}"
                                   placeholder="Google Maps embed iframe src URL">
                        </div>
                    </div>

                    <div class="mb-3">
                        <label class="form-label">Accommodation &amp; Transportation Info</label>
                        <textarea class="form-control" rows="5" th:field="*{travelInfoHtml}"></textarea>
                    </div>
```

Also change the form's submit action to be edit-aware — change:
```html
<form th:action="@{/admin/conference/save}" th:object="${conferenceForm}" method="post">
```
to:
```html
<form th:action="${editingConferenceId != null ? '/admin/conference/' + editingConferenceId + '/edit' : '/admin/conference/save'}" th:object="${conferenceForm}" method="post">
```

And change the submit button text to be edit-aware — change:
```html
<button type="submit" class="btn btn-success btn-lg">Create Conference</button>
```
to:
```html
<button type="submit" class="btn btn-success btn-lg" th:text="${editingConferenceId != null ? 'Save Changes' : 'Create Conference'}">Create Conference</button>
```

- [ ] **Step 4: Create `admin/conference_list.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head>
    <meta charset="UTF-8">
    <title>Conferences - Conference CMS</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/app.css" rel="stylesheet">
</head>

<body>
    <nav class="navbar navbar-dark bg-dark">
        <div class="container">
            <a class="navbar-brand" href="/admin/dashboard">CMS Admin</a>
            <a class="btn btn-outline-light" href="/logout">Logout</a>
        </div>
    </nav>

    <div class="container mt-4">
        <div class="d-flex justify-content-between align-items-center mb-3">
            <h2>Conferences</h2>
            <a href="/admin/conference/new" class="btn btn-success">New Conference</a>
        </div>
        <table class="table table-striped">
            <thead>
                <tr>
                    <th>Title</th>
                    <th>Dates</th>
                    <th>Active</th>
                    <th>Actions</th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="c : ${conferences}">
                    <td th:text="${c.title}"></td>
                    <td th:text="${c.startDate} + ' - ' + ${c.endDate}"></td>
                    <td>
                        <span th:if="${c.active}" class="badge bg-success">Active</span>
                        <span th:unless="${c.active}" class="badge bg-secondary">Inactive</span>
                    </td>
                    <td>
                        <a th:href="@{'/admin/conference/' + ${c.id} + '/edit'}" class="btn btn-sm btn-primary">Edit</a>
                    </td>
                </tr>
            </tbody>
        </table>
    </div>
</body>

</html>
```

- [ ] **Step 5: Link the conference list from `admin/dashboard.html`**

Edit `src/main/resources/templates/admin/dashboard.html` — add a nav link immediately before the existing "Registrations" link:

```html
            <a class="btn btn-outline-light me-2" href="/admin/conference/list">Conferences</a>
```

- [ ] **Step 6: Write controller tests**

Add to `src/test/java/org/confcms/cms/web/controller/AdminConferenceControllerTest.java`:

```java
    @Test
    void editConferenceFormPrefillsAllFieldsIncludingContent() {
        Conference conference = new Conference();
        conference.setId(9L);
        conference.setTitle("Existing Conf");
        conference.setVenue("Existing Venue");
        conference.setAboutHtml("<p>About text</p>");
        conference.setCallForPapersHtml("<p>CFP text</p>");
        conference.setVenueAddress("123 Main St");
        conference.setVenueMapEmbedUrl("https://maps.example.com/embed");
        conference.setTravelInfoHtml("<p>Travel text</p>");

        when(conferenceRepository.findById(9L)).thenReturn(Optional.of(conference));
        when(committeeService.getCommitteeForConference(conference)).thenReturn(List.of());
        when(userRepository.findAll()).thenReturn(Collections.emptyList());
        when(conferenceRepository.findAll()).thenReturn(Collections.emptyList());

        Model model = new ExtendedModelMap();
        controller.editConferenceForm(9L, model);

        AdminConferenceController.ConferenceForm form =
                (AdminConferenceController.ConferenceForm) model.getAttribute("conferenceForm");
        assertThat(form.getTitle()).isEqualTo("Existing Conf");
        assertThat(form.getAboutHtml()).isEqualTo("<p>About text</p>");
        assertThat(form.getCallForPapersHtml()).isEqualTo("<p>CFP text</p>");
        assertThat(form.getVenueAddress()).isEqualTo("123 Main St");
        assertThat(form.getVenueMapEmbedUrl()).isEqualTo("https://maps.example.com/embed");
        assertThat(form.getTravelInfoHtml()).isEqualTo("<p>Travel text</p>");
        assertThat(model.getAttribute("editingConferenceId")).isEqualTo(9L);
    }

    @Test
    void updateConferenceSavesContentFieldsOntoExistingConference() {
        Conference existing = new Conference();
        existing.setId(9L);
        when(conferenceRepository.findById(9L)).thenReturn(Optional.of(existing));
        when(conferenceService.saveConference(any())).thenAnswer(inv -> inv.getArgument(0));

        AdminConferenceController.ConferenceForm form = new AdminConferenceController.ConferenceForm();
        form.setTitle("Updated Title");
        form.setVenue("Updated Venue");
        form.setStartDate(LocalDate.now());
        form.setEndDate(LocalDate.now().plusDays(1));
        form.setContactEmail("a@b.com");
        form.setPaymentProvider(PaymentProvider.FREE);
        form.setAboutHtml("<p>Updated about</p>");

        String view = controller.updateConference(9L, form);

        assertThat(view).isEqualTo("redirect:/admin/conference/list");
        assertThat(existing.getTitle()).isEqualTo("Updated Title");
        assertThat(existing.getAboutHtml()).isEqualTo("<p>Updated about</p>");
    }
```

Confirm `Model`/`ExtendedModelMap` imports are already present in this test file from the earlier clone-feature tests (they are, per this session's prior work on roadmap item #13).

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.AdminConferenceControllerTest"`
Expected: PASS, 8 tests green (6 existing + 2 new).

- [ ] **Step 8: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 230 tests passing.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/AdminConferenceController.java src/main/resources/templates/admin/conference_form.html src/main/resources/templates/admin/conference_list.html src/main/resources/templates/admin/dashboard.html src/test/java/org/confcms/cms/web/controller/AdminConferenceControllerTest.java
git commit -m "feat: add conference edit action and public-content admin fields"
```

---

### Task 9: Sponsor admin CRUD

**Files:**
- Create: `src/main/java/org/confcms/cms/web/controller/AdminSponsorController.java`
- Create: `src/main/resources/templates/admin/sponsors.html`
- Create: `src/main/resources/templates/admin/sponsor_form.html`
- Test: Create `src/test/java/org/confcms/cms/web/controller/AdminSponsorControllerTest.java`

- [ ] **Step 1: Create `AdminSponsorController`**

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.Sponsor;
import org.confcms.cms.domain.SponsorTier;
import org.confcms.cms.repository.ConferenceRepository;
import org.confcms.cms.repository.SponsorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/conference/{conferenceId}/sponsors")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSponsorController {

    private final SponsorRepository sponsorRepository;
    private final ConferenceRepository conferenceRepository;

    @GetMapping
    public String list(@PathVariable Long conferenceId, Model model) {
        Conference conference = loadConference(conferenceId);
        model.addAttribute("conference", conference);
        model.addAttribute("sponsors", sponsorRepository.findByConferenceIdOrderByDisplayOrderAsc(conferenceId));
        return "admin/sponsors";
    }

    @GetMapping("/new")
    public String newForm(@PathVariable Long conferenceId, Model model) {
        model.addAttribute("conference", loadConference(conferenceId));
        model.addAttribute("sponsor", new Sponsor());
        model.addAttribute("tiers", SponsorTier.values());
        return "admin/sponsor_form";
    }

    @PostMapping("/save")
    public String save(@PathVariable Long conferenceId, @ModelAttribute Sponsor sponsor) {
        sponsor.setConference(loadConference(conferenceId));
        sponsorRepository.save(sponsor);
        return "redirect:/admin/conference/" + conferenceId + "/sponsors";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long conferenceId, @PathVariable Long id, Model model) {
        Sponsor sponsor = sponsorRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Sponsor not found"));
        model.addAttribute("conference", loadConference(conferenceId));
        model.addAttribute("sponsor", sponsor);
        model.addAttribute("tiers", SponsorTier.values());
        return "admin/sponsor_form";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long conferenceId, @PathVariable Long id) {
        sponsorRepository.deleteById(id);
        return "redirect:/admin/conference/" + conferenceId + "/sponsors";
    }

    private Conference loadConference(Long conferenceId) {
        return conferenceRepository.findById(conferenceId)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));
    }
}
```

Note: `save` handles BOTH create and edit — a Spring `@ModelAttribute Sponsor` bound from a form with a hidden `id` field will already carry the existing entity's id on edit-submit, and `sponsorRepository.save(...)` performs an upsert based on whether the id is present (standard Spring Data JPA behavior) — no separate `POST /{id}/edit` endpoint is needed for the save itself, only for rendering the pre-filled edit form (`editForm`, already defined above). The `sponsor_form.html` template (Step 2) includes a hidden `id` input to make this work.

- [ ] **Step 2: Create `admin/sponsors.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head>
    <meta charset="UTF-8">
    <title>Sponsors - Conference CMS</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/app.css" rel="stylesheet">
</head>

<body>
    <nav class="navbar navbar-dark bg-dark">
        <div class="container">
            <a class="navbar-brand" href="/admin/dashboard">CMS Admin</a>
            <a class="btn btn-outline-light" href="/logout">Logout</a>
        </div>
    </nav>

    <div class="container mt-4">
        <div class="d-flex justify-content-between align-items-center mb-3">
            <h2 th:text="'Sponsors - ' + ${conference.title}">Sponsors</h2>
            <a th:href="@{'/admin/conference/' + ${conference.id} + '/sponsors/new'}" class="btn btn-success">Add Sponsor</a>
        </div>
        <table class="table table-striped">
            <thead>
                <tr>
                    <th>Name</th>
                    <th>Tier</th>
                    <th>Order</th>
                    <th>Actions</th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="s : ${sponsors}">
                    <td th:text="${s.name}"></td>
                    <td th:text="${s.tier}"></td>
                    <td th:text="${s.displayOrder}"></td>
                    <td>
                        <a th:href="@{'/admin/conference/' + ${conference.id} + '/sponsors/' + ${s.id} + '/edit'}" class="btn btn-sm btn-primary">Edit</a>
                        <form th:action="@{'/admin/conference/' + ${conference.id} + '/sponsors/' + ${s.id} + '/delete'}" method="post" class="d-inline">
                            <button type="submit" class="btn btn-sm btn-danger">Delete</button>
                        </form>
                    </td>
                </tr>
                <tr th:if="${sponsors.isEmpty()}">
                    <td colspan="4" class="text-muted">No sponsors yet.</td>
                </tr>
            </tbody>
        </table>
    </div>
</body>

</html>
```

- [ ] **Step 3: Create `admin/sponsor_form.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head>
    <meta charset="UTF-8">
    <title>Sponsor - Conference CMS</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/app.css" rel="stylesheet">
</head>

<body>
    <nav class="navbar navbar-dark bg-dark">
        <div class="container">
            <a class="navbar-brand" href="/admin/dashboard">CMS Admin</a>
            <a class="btn btn-outline-light" href="/logout">Logout</a>
        </div>
    </nav>

    <div class="container mt-4">
        <h2 th:text="${sponsor.id} != null ? 'Edit Sponsor' : 'Add Sponsor'">Sponsor</h2>
        <form th:action="@{'/admin/conference/' + ${conference.id} + '/sponsors/save'}" th:object="${sponsor}" method="post">
            <input type="hidden" th:field="*{id}">

            <div class="mb-3">
                <label class="form-label">Name</label>
                <input type="text" class="form-control" th:field="*{name}" required>
            </div>

            <div class="mb-3">
                <label class="form-label">Logo URL</label>
                <input type="text" class="form-control" th:field="*{logoUrl}" required>
            </div>

            <div class="mb-3">
                <label class="form-label">Website URL (optional)</label>
                <input type="text" class="form-control" th:field="*{websiteUrl}">
            </div>

            <div class="mb-3">
                <label class="form-label">Tier</label>
                <select class="form-select" th:field="*{tier}">
                    <option th:each="t : ${tiers}" th:value="${t}" th:text="${t}"></option>
                </select>
            </div>

            <div class="mb-3">
                <label class="form-label">Display Order</label>
                <input type="number" class="form-control" th:field="*{displayOrder}">
            </div>

            <button type="submit" class="btn btn-success">Save</button>
            <a th:href="@{'/admin/conference/' + ${conference.id} + '/sponsors'}" class="btn btn-secondary">Cancel</a>
        </form>
    </div>
</body>

</html>
```

- [ ] **Step 4: Link sponsor management from `admin/conference_list.html`**

Edit `src/main/resources/templates/admin/conference_list.html` (created in Task 8) — add a "Sponsors" link next to the existing "Edit" link in the Actions column:

```html
                        <a th:href="@{'/admin/conference/' + ${c.id} + '/sponsors'}" class="btn btn-sm btn-secondary">Sponsors</a>
```

- [ ] **Step 5: Write controller tests**

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.Sponsor;
import org.confcms.cms.domain.SponsorTier;
import org.confcms.cms.repository.ConferenceRepository;
import org.confcms.cms.repository.SponsorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminSponsorControllerTest {

    @Mock
    private SponsorRepository sponsorRepository;
    @Mock
    private ConferenceRepository conferenceRepository;

    private AdminSponsorController controller;
    private Conference conference;

    @BeforeEach
    void setUp() {
        controller = new AdminSponsorController(sponsorRepository, conferenceRepository);
        conference = new Conference();
        conference.setId(1L);
        conference.setTitle("Test Conf");
    }

    @Test
    void listShowsSponsorsForConference() {
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        Sponsor sponsor = new Sponsor();
        when(sponsorRepository.findByConferenceIdOrderByDisplayOrderAsc(1L)).thenReturn(List.of(sponsor));

        Model model = new ExtendedModelMap();
        String view = controller.list(1L, model);

        assertThat(view).isEqualTo("admin/sponsors");
        assertThat((List<?>) model.getAttribute("sponsors")).containsExactly(sponsor);
    }

    @Test
    void saveAttachesConferenceAndPersists() {
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        Sponsor sponsor = new Sponsor();
        sponsor.setName("Acme Corp");

        String view = controller.save(1L, sponsor);

        assertThat(view).isEqualTo("redirect:/admin/conference/1/sponsors");
        assertThat(sponsor.getConference()).isEqualTo(conference);
        verify(sponsorRepository).save(sponsor);
    }

    @Test
    void deleteRemovesSponsorById() {
        String view = controller.delete(1L, 5L);

        assertThat(view).isEqualTo("redirect:/admin/conference/1/sponsors");
        verify(sponsorRepository).deleteById(5L);
    }

    @Test
    void editFormLoadsExistingSponsor() {
        Sponsor sponsor = new Sponsor();
        sponsor.setId(5L);
        sponsor.setTier(SponsorTier.GOLD);
        when(sponsorRepository.findById(5L)).thenReturn(Optional.of(sponsor));
        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));

        Model model = new ExtendedModelMap();
        String view = controller.editForm(1L, 5L, model);

        assertThat(view).isEqualTo("admin/sponsor_form");
        assertThat(model.getAttribute("sponsor")).isEqualTo(sponsor);
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests "*AdminSponsorControllerTest*"`
Expected: PASS, 4 tests green.

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 234 tests passing.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/AdminSponsorController.java src/main/resources/templates/admin/sponsors.html src/main/resources/templates/admin/sponsor_form.html src/main/resources/templates/admin/conference_list.html src/test/java/org/confcms/cms/web/controller/AdminSponsorControllerTest.java
git commit -m "feat: add sponsor admin CRUD"
```

---

### Task 10: Speaker admin CRUD

**Files:**
- Create: `src/main/java/org/confcms/cms/web/controller/AdminSpeakerController.java`
- Create: `src/main/resources/templates/admin/speakers.html`
- Create: `src/main/resources/templates/admin/speaker_form.html`
- Modify: `src/main/resources/templates/admin/conference_list.html`
- Test: Create `src/test/java/org/confcms/cms/web/controller/AdminSpeakerControllerTest.java`

- [ ] **Step 1: Create `AdminSpeakerController`**

Mirror `AdminSponsorController` from Task 9 exactly, substituting `Speaker`/`SpeakerRepository`/`SpeakerType` for `Sponsor`/`SponsorRepository`/`SponsorTier`, and `/admin/conference/{conferenceId}/speakers` for the route prefix:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.Speaker;
import org.confcms.cms.domain.SpeakerType;
import org.confcms.cms.repository.ConferenceRepository;
import org.confcms.cms.repository.SpeakerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/conference/{conferenceId}/speakers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminSpeakerController {

    private final SpeakerRepository speakerRepository;
    private final ConferenceRepository conferenceRepository;

    @GetMapping
    public String list(@PathVariable Long conferenceId, Model model) {
        Conference conference = loadConference(conferenceId);
        model.addAttribute("conference", conference);
        model.addAttribute("speakers", speakerRepository.findByConferenceIdOrderByDisplayOrderAsc(conferenceId));
        return "admin/speakers";
    }

    @GetMapping("/new")
    public String newForm(@PathVariable Long conferenceId, Model model) {
        model.addAttribute("conference", loadConference(conferenceId));
        model.addAttribute("speaker", new Speaker());
        model.addAttribute("types", SpeakerType.values());
        return "admin/speaker_form";
    }

    @PostMapping("/save")
    public String save(@PathVariable Long conferenceId, @ModelAttribute Speaker speaker) {
        speaker.setConference(loadConference(conferenceId));
        speakerRepository.save(speaker);
        return "redirect:/admin/conference/" + conferenceId + "/speakers";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long conferenceId, @PathVariable Long id, Model model) {
        Speaker speaker = speakerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Speaker not found"));
        model.addAttribute("conference", loadConference(conferenceId));
        model.addAttribute("speaker", speaker);
        model.addAttribute("types", SpeakerType.values());
        return "admin/speaker_form";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long conferenceId, @PathVariable Long id) {
        speakerRepository.deleteById(id);
        return "redirect:/admin/conference/" + conferenceId + "/speakers";
    }

    private Conference loadConference(Long conferenceId) {
        return conferenceRepository.findById(conferenceId)
                .orElseThrow(() -> new IllegalArgumentException("Conference not found"));
    }
}
```

- [ ] **Step 2: Create `admin/speakers.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head>
    <meta charset="UTF-8">
    <title>Speakers - Conference CMS</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/app.css" rel="stylesheet">
</head>

<body>
    <nav class="navbar navbar-dark bg-dark">
        <div class="container">
            <a class="navbar-brand" href="/admin/dashboard">CMS Admin</a>
            <a class="btn btn-outline-light" href="/logout">Logout</a>
        </div>
    </nav>

    <div class="container mt-4">
        <div class="d-flex justify-content-between align-items-center mb-3">
            <h2 th:text="'Speakers - ' + ${conference.title}">Speakers</h2>
            <a th:href="@{'/admin/conference/' + ${conference.id} + '/speakers/new'}" class="btn btn-success">Add Speaker</a>
        </div>
        <table class="table table-striped">
            <thead>
                <tr>
                    <th>Name</th>
                    <th>Type</th>
                    <th>Order</th>
                    <th>Actions</th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="s : ${speakers}">
                    <td th:text="${s.fullName}"></td>
                    <td th:text="${s.type}"></td>
                    <td th:text="${s.displayOrder}"></td>
                    <td>
                        <a th:href="@{'/admin/conference/' + ${conference.id} + '/speakers/' + ${s.id} + '/edit'}" class="btn btn-sm btn-primary">Edit</a>
                        <form th:action="@{'/admin/conference/' + ${conference.id} + '/speakers/' + ${s.id} + '/delete'}" method="post" class="d-inline">
                            <button type="submit" class="btn btn-sm btn-danger">Delete</button>
                        </form>
                    </td>
                </tr>
                <tr th:if="${speakers.isEmpty()}">
                    <td colspan="4" class="text-muted">No speakers yet.</td>
                </tr>
            </tbody>
        </table>
    </div>
</body>

</html>
```

- [ ] **Step 3: Create `admin/speaker_form.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head>
    <meta charset="UTF-8">
    <title>Speaker - Conference CMS</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/app.css" rel="stylesheet">
</head>

<body>
    <nav class="navbar navbar-dark bg-dark">
        <div class="container">
            <a class="navbar-brand" href="/admin/dashboard">CMS Admin</a>
            <a class="btn btn-outline-light" href="/logout">Logout</a>
        </div>
    </nav>

    <div class="container mt-4">
        <h2 th:text="${speaker.id} != null ? 'Edit Speaker' : 'Add Speaker'">Speaker</h2>
        <form th:action="@{'/admin/conference/' + ${conference.id} + '/speakers/save'}" th:object="${speaker}" method="post">
            <input type="hidden" th:field="*{id}">

            <div class="mb-3">
                <label class="form-label">Full Name</label>
                <input type="text" class="form-control" th:field="*{fullName}" required>
            </div>

            <div class="mb-3">
                <label class="form-label">Title (optional)</label>
                <input type="text" class="form-control" th:field="*{title}" placeholder="e.g. Professor, MIT">
            </div>

            <div class="mb-3">
                <label class="form-label">Bio</label>
                <textarea class="form-control" rows="4" th:field="*{bio}"></textarea>
            </div>

            <div class="mb-3">
                <label class="form-label">Photo URL</label>
                <input type="text" class="form-control" th:field="*{photoUrl}">
            </div>

            <div class="mb-3">
                <label class="form-label">Type</label>
                <select class="form-select" th:field="*{type}">
                    <option th:each="t : ${types}" th:value="${t}" th:text="${t}"></option>
                </select>
            </div>

            <div class="mb-3">
                <label class="form-label">Display Order</label>
                <input type="number" class="form-control" th:field="*{displayOrder}">
            </div>

            <button type="submit" class="btn btn-success">Save</button>
            <a th:href="@{'/admin/conference/' + ${conference.id} + '/speakers'}" class="btn btn-secondary">Cancel</a>
        </form>
    </div>
</body>

</html>
```

- [ ] **Step 4: Link speaker management from `admin/conference_list.html`**

Add next to the "Sponsors" link added in Task 9:

```html
                        <a th:href="@{'/admin/conference/' + ${c.id} + '/speakers'}" class="btn btn-sm btn-secondary">Speakers</a>
```

- [ ] **Step 5: Write controller tests**

Mirror `AdminSponsorControllerTest` from Task 9 exactly, substituting `Speaker`/`SpeakerRepository`/`SpeakerType.KEYNOTE` for `Sponsor`/`SponsorRepository`/`SponsorTier.GOLD`, and `/admin/conference/1/speakers` for the redirect path. Create `src/test/java/org/confcms/cms/web/controller/AdminSpeakerControllerTest.java` with the equivalent four tests (`listShowsSpeakersForConference`, `saveAttachesConferenceAndPersists`, `deleteRemovesSpeakerById`, `editFormLoadsExistingSpeaker`).

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests "*AdminSpeakerControllerTest*"`
Expected: PASS, 4 tests green.

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, 238 tests passing.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/AdminSpeakerController.java src/main/resources/templates/admin/speakers.html src/main/resources/templates/admin/speaker_form.html src/main/resources/templates/admin/conference_list.html src/test/java/org/confcms/cms/web/controller/AdminSpeakerControllerTest.java
git commit -m "feat: add speaker admin CRUD"
```

---

### Task 11: Final manual end-to-end verification

**Files:** none (verification only)

- [ ] **Step 1: Boot the dev profile**

```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun > /tmp/public-site-final-boot.log 2>&1 &
```
Poll for `Started ConferenceCmsApplication` (up to 60s), confirm no `ERROR`/`Exception`.

- [ ] **Step 2: Walk every public page as an unauthenticated visitor**

```bash
for path in / /about /committee /speakers /sponsors /call-for-papers /venue /register /past-conferences /contact; do
  curl -s -o /dev/null -w "%{http_code} $path\n" "http://localhost:8080$path"
done
```
Expected: every route returns `200` (not `404`/`500`). This is the concrete regression check for the exact bug this plan set out to fix (five of these routes 500'd before this plan started).

- [ ] **Step 3: Log in as admin, create a sponsor and a speaker, edit the active conference's content fields**

Using real cookie-based login (`admin@example.com` / `admin`, per this session's established pattern):
```bash
rm -f /tmp/pw-cookies.txt /tmp/pw-login.html
curl -s -c /tmp/pw-cookies.txt -o /tmp/pw-login.html http://localhost:8080/login
CSRF=$(grep -o 'name="_csrf" value="[^"]*"' /tmp/pw-login.html | sed -E 's/.*value="([^"]*)"/\1/')
curl -s -b /tmp/pw-cookies.txt -c /tmp/pw-cookies.txt -D - -o /dev/null -X POST http://localhost:8080/login \
  --data-urlencode "username=admin@example.com" --data-urlencode "password=admin" \
  --data-urlencode "_csrf=$CSRF" | grep -i "^location"
```
Expected: `Location: http://localhost:8080/dashboard` (confirms login succeeded — this session's seed-password fix from an earlier roadmap item is still in effect).

Find the active seeded conference's id via `curl -s -b /tmp/pw-cookies.txt http://localhost:8080/admin/conference/list`, then:
- Create a sponsor via `POST /admin/conference/{id}/sponsors/save` with `name`, `logoUrl`, `tier=GOLD`.
- Create a speaker via `POST /admin/conference/{id}/speakers/save` with `fullName`, `type=KEYNOTE`.
- Edit the conference via `POST /admin/conference/{id}/edit` including `aboutHtml=<p>Test about content</p>` and the other required existing fields (title, venue, dates, contactEmail, chairUserId, paymentProvider) — re-fetch `GET /admin/conference/{id}/edit` first to get a fresh CSRF token and the existing chair id.

- [ ] **Step 4: Confirm the new content renders on the public pages**

```bash
curl -s -b /tmp/pw-cookies.txt http://localhost:8080/about | grep -q "Test about content" && echo "about OK"
curl -s -b /tmp/pw-cookies.txt http://localhost:8080/sponsors | grep -q "<the sponsor name used above>" && echo "sponsors OK"
curl -s -b /tmp/pw-cookies.txt http://localhost:8080/speakers | grep -q "<the speaker name used above>" && echo "speakers OK"
```

- [ ] **Step 5: Confirm the past-conferences archive works against real seed data**

`data-dev.sql` seeds three conferences (ids 1-3, per this session's earlier work on roadmap item #13's manual verification) — confirm at least one is `is_active = FALSE` and appears at `GET /past-conferences`, and that `GET /past-conferences/{that-id}/about` returns 200 and shows that specific (non-active) conference's title, not the active one's.

- [ ] **Step 6: Stop the app and run the full suite one final time**

```bash
PID=$(lsof -i :8080 -sTCP:LISTEN -t)
kill -9 $PID
lsof -i :8080 -sTCP:LISTEN -t || echo "PORT FREE"
./gradlew test
```
Expected: BUILD SUCCESSFUL, 238 tests passing, 0 failures.

- [ ] **Step 7: Clean up scratch files**

```bash
rm -f /tmp/pw-cookies.txt /tmp/pw-login.html /tmp/public-site-task1-boot.log /tmp/public-site-final-boot.log
```

No commit for this task — it is verification only. If any step fails, fix the underlying issue in the relevant earlier task's files and re-run this task's steps from the top.
