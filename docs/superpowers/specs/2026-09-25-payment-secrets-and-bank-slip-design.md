# Payment Secrets Encryption & Bank-Slip Workflow — Design Spec

## 1. Scope

This is roadmap item #7 ("payment credentials externalized + bank-slip workflow"), scoped down from the gap-analysis doc's original framing after confirming the actual state of the code: **Stripe/PayPal checkout integration does not exist today** — no SDK calls anywhere in the codebase, `RegistrationController.processRegistration` ignores the payment provider entirely and unconditionally creates a `PENDING` registration regardless of what `ticketType`/provider was selected. Building real gateway integration is a separate, much larger effort with no existing scaffolding to build on, and is explicitly deferred.

In scope:

1. **Encrypt `ConferencePaymentConfig`'s Stripe/PayPal secret columns at rest** — they are currently plain, unmasked `String` columns populated directly from an admin HTML form.
2. **Build the LOCAL_BANK bank-slip workflow end to end** — registrant uploads proof of payment, admin reviews and approves/rejects it. This does not exist at all today: `register.html`'s `LOCAL_BANK` branch only displays static bank details with no upload form, there is no admin UI for registrations anywhere in the codebase, and `RegistrationService.markAsPaid()` — the obvious seam for "admin confirms payment" — is unused dead code.
3. **Add a `Conference` reference to `Registration`** — discovered as a real gap while finalizing this spec: `Registration` has no link to which conference it belongs to, which both the double-submission guard (Section 5) and the admin list page need to be correct once more than one conference's data exists.
4. **Fix the `VIRTUAL` ticket-type crash** — found during research: `register.html`'s ticket dropdown offers `VIRTUAL` ($25), but `RegistrationService.TICKET_PRICES` only has `REGULAR`/`STUDENT`, so selecting `VIRTUAL` throws `IllegalArgumentException: Unknown ticket type`. Directly in the file this spec already touches.

**Explicitly out of scope:**
- Real Stripe Checkout / Payment Intents integration.
- Real PayPal Orders API integration.
- Field-level encryption of anything beyond the two Stripe/PayPal secret columns (`bankDetails`, `stripePublishableKey`, `paypalClientId` are not credentials the app authenticates with — they stay plaintext).
- Encryption-key rotation tooling (documented as a known limitation, not built).
- Editing an existing conference's payment config after creation (`AdminConferenceController` today only supports create, no edit endpoint — that gap predates this spec and is not reopened here).

## 2. Secret encryption

### 2.1 `PaymentSecretConverter`

New `@Converter` in `org.confcms.cms.domain` (co-located with `ConferencePaymentConfig`, matching this codebase's convention of keeping small support classes next to the entity they serve):

```java
package org.confcms.cms.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
@Converter(autoApply = false)
public class PaymentSecretConverter implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKeySpec key;

    public PaymentSecretConverter(@Value("${app.secrets.encryption-key}") String encryptionKey) {
        byte[] keyBytes = Base64.getDecoder().decode(encryptionKey);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "app.secrets.encryption-key must decode to exactly 32 bytes (AES-256) -- "
                            + "generate one with: openssl rand -base64 32");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt payment secret", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt payment secret", e);
        }
    }
}
```

`autoApply = false`: this converter is deliberately not auto-applied to every `String` field in the codebase, only to the two fields that opt in via `@Convert` (Section 2.2) — auto-applying would silently start encrypting unrelated `String` columns on other entities the moment this class exists, which is exactly the kind of implicit, hard-to-notice behavior change this codebase's other work has consistently avoided.

Spring manages this as a `@Component` (not a plain JPA-instantiated converter) specifically so `@Value` injection works — JPA's own converter instantiation doesn't go through Spring's DI container by default; `@Converter` + `@Component` together, combined with `spring.jpa.properties.hibernate.....` needing no extra config, is the standard pattern for a Spring-aware `AttributeConverter` (confirmed: Hibernate's `ConverterRegistrationHelper` picks up Spring-managed converter beans in a Spring Boot context without extra wiring, since `spring-boot-starter-data-jpa` auto-configures the `LocalContainerEntityManagerFactoryBean` to scan the Spring context for CDI/Spring-managed converters).

### 2.2 Wiring into `ConferencePaymentConfig`

Modify `src/main/java/org/confcms/cms/domain/ConferencePaymentConfig.java`:

```java
    // Stripe
    private String stripePublishableKey;

    @Convert(converter = PaymentSecretConverter.class)
    private String stripeSecretKey;

    // PayPal
    private String paypalClientId;

    @Convert(converter = PaymentSecretConverter.class)
    private String paypalClientSecret;
```

`stripePublishableKey` and `paypalClientId` are unchanged — they are publishable-by-design identifiers, not secrets, and encrypting them would add ciphertext overhead for no confidentiality benefit.

No change to `AdminConferenceController` or `admin/conference_form.html` — the converter is transparent at the JPA layer; `ConferenceForm`'s plain `String stripeSecretKey`/`paypalClientSecret` fields still bind normally, `saveConference` still calls `paymentConfig.setStripeSecretKey(form.getStripeSecretKey())` unchanged, and Hibernate encrypts on the `INSERT`/`UPDATE`, decrypts on any `SELECT`.

### 2.3 Key provisioning and fail-fast behavior

`app.secrets.encryption-key` is a required property with **no default** in `application.properties` — if it's unset, `PaymentSecretConverter`'s constructor throws during context startup (Spring bean creation failure), which fails the whole application boot. This is intentional: a `ConferencePaymentConfig` row with a null-when-it-should-be-encrypted secret is worse than a refusal to start, and this matches the "fail fast at boundaries" discipline already established this session (e.g. `FirstRunAdminInitializer`'s required env var pattern).

Add to `application.properties`, in the Database/security section:

```properties
# Required: base64-encoded 32-byte AES-256 key used to encrypt Stripe/PayPal secret
# columns on conference_payment_configs at rest. Generate with:
#   openssl rand -base64 32
# There is no default -- the app refuses to start without it, since a payment
# secret written without this key would be either unencrypted or unrecoverable.
app.secrets.encryption-key=${PAYMENT_SECRETS_KEY}
```

`application-dev.properties` gets a fixed, checked-in dev-only key (not a real secret — this is local H2, seeded data, never handles a real Stripe/PayPal credential):

```properties
# Fixed dev-only key -- never use this value outside local development.
app.secrets.encryption-key=ZGV2LW9ubHktZW5jcnlwdGlvbi1rZXktbm90LWZvci1wcm9k
```

(That's `Base64.getEncoder().encodeToString("dev-only-encryption-key-not-for-prod".getBytes())` truncated/padded to a valid 32-byte decode — exact value generated and verified during implementation, Task 1.)

### 2.4 Existing plaintext data

Any `ConferencePaymentConfig` row already saved with a plaintext secret (there are none in this codebase's seed data — `data.sql`/`data-dev.sql` create no `ConferencePaymentConfig` rows) will, on next read, fail to decrypt (the plaintext string, when Base64-decoded and IV-split, will not produce valid GCM ciphertext) and throw `IllegalStateException`. Since no seed data creates this entity, this is a non-issue for this codebase's current state — documented here only so a future migration concern is legible, not because it blocks anything today.

## 3. `Registration` → `Conference` link

### 3.1 Schema change

Modify `src/main/java/org/confcms/cms/registration/domain/Registration.java`, adding a field after `user`:

```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;
```

(Import `org.confcms.cms.domain.Conference` alongside the existing `org.confcms.cms.domain.User` import.) `ddl-auto=update` will add the `conference_id` column; existing rows (none in seed data, confirmed) need no backfill.

### 3.2 Repository

Modify `src/main/java/org/confcms/cms/registration/repository/RegistrationRepository.java`:

```java
@Repository
public interface RegistrationRepository extends JpaRepository<Registration, Long> {
    List<Registration> findByUserId(Long userId);
    List<Registration> findByConferenceIdAndPaymentStatus(Long conferenceId, PaymentStatus paymentStatus);
    Optional<Registration> findByUserIdAndConferenceIdAndPaymentStatusNot(Long userId, Long conferenceId, PaymentStatus paymentStatus);
}
```

`findByConferenceIdAndPaymentStatus` backs the admin verification queue (Section 5.1). `findByUserIdAndConferenceIdAndPaymentStatusNot` backs the double-submission guard (Section 6): "does this user have any registration for this conference that isn't `FAILED`."

## 4. Bank-slip upload (registrant side)

### 4.1 `PaymentStatus` gains `AWAITING_VERIFICATION`

Modify `src/main/java/org/confcms/cms/registration/domain/PaymentStatus.java`:

```java
package org.confcms.cms.registration.domain;

public enum PaymentStatus {
    PENDING,
    AWAITING_VERIFICATION,
    PAID,
    FAILED,
    REFUNDED
}
```

Semantics: `PENDING` is the state for FREE/STRIPE/PAYPAL registrations (unchanged from today, since those flows have no gateway integration to advance them further). `AWAITING_VERIFICATION` is LOCAL_BANK-specific: set the moment a bank slip is uploaded (initial submission or re-upload after rejection), cleared only by an admin's approve (`PAID`) or reject (`FAILED`) action.

### 4.2 `Registration` gains slip fields

Modify `src/main/java/org/confcms/cms/registration/domain/Registration.java` further:

```java
    private String bankSlipPath;
    private String bankSlipOriginalFilename;
    private String rejectionReason;
```

No versioning/history collection — a re-upload overwrites `bankSlipPath`/`bankSlipOriginalFilename` in place and clears `rejectionReason`, matching the existing single-field shape of `invoicePath` rather than `PaperVersion`'s collection-based history (papers need version history for the review process; a bank slip's only prior state that matters is "was it rejected and why," which `rejectionReason` alone captures).

### 4.3 Broadened file validation

New method in `RegistrationService`, structurally parallel to `SubmissionService.validatePdf` but recognizing three magic-byte signatures instead of one (bank slips are commonly phone photos, not scans):

```java
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G'};

    private void validateBankSlip(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A bank slip file is required for bank transfer registrations");
        }
        try (var in = file.getInputStream()) {
            byte[] header = new byte[8];
            int read = in.read(header);
            if (read < 4) {
                throw new IllegalArgumentException("Uploaded file is too small to be a valid bank slip");
            }
            boolean isPdf = startsWith(header, PDF_SIGNATURE);
            boolean isJpeg = startsWith(header, JPEG_SIGNATURE);
            boolean isPng = startsWith(header, PNG_SIGNATURE);
            if (!isPdf && !isJpeg && !isPng) {
                throw new IllegalArgumentException("Bank slip must be a PDF, JPEG, or PNG file");
            }
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            throw new RuntimeException("File validation failed", e);
        }
    }

    private boolean startsWith(byte[] header, byte[] signature) {
        if (header.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (header[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
```

(`java.nio.charset.StandardCharsets` import added to `RegistrationService`.) File size is governed by the existing global `spring.servlet.multipart.max-file-size=50MB` — no per-upload-type override needed, matching how paper PDF upload has no tighter limit of its own either.

### 4.4 `RegistrationService.register` signature change

Modify `src/main/java/org/confcms/cms/registration/service/RegistrationService.java`:

```java
    private static final Map<String, BigDecimal> TICKET_PRICES = Map.of(
            "REGULAR", new BigDecimal("100"),
            "STUDENT", new BigDecimal("50"),
            "VIRTUAL", new BigDecimal("25")
    );

    private final RegistrationRepository registrationRepository;
    private final FileStorageService fileStorageService;

    @Transactional
    public Registration register(User user, Conference conference, String ticketType, MultipartFile bankSlip) {
        BigDecimal amount = TICKET_PRICES.get(ticketType);
        if (amount == null) {
            throw new IllegalArgumentException("Unknown ticket type: " + ticketType);
        }

        registrationRepository.findByUserIdAndConferenceIdAndPaymentStatusNot(
                        user.getId(), conference.getId(), PaymentStatus.FAILED)
                .ifPresent(existing -> {
                    throw new IllegalArgumentException("You already have a registration for this conference");
                });

        Registration registration = new Registration();
        registration.setUser(user);
        registration.setConference(conference);
        registration.setTicketType(ticketType);
        registration.setAmount(amount);

        PaymentProvider provider = conference.getPaymentConfig() != null
                ? conference.getPaymentConfig().getProvider() : PaymentProvider.FREE;

        if (provider == PaymentProvider.LOCAL_BANK) {
            validateBankSlip(bankSlip);
            String filePath = fileStorageService.store(bankSlip);
            registration.setBankSlipPath(filePath);
            registration.setBankSlipOriginalFilename(bankSlip.getOriginalFilename());
            registration.setPaymentStatus(PaymentStatus.AWAITING_VERIFICATION);
        } else {
            registration.setPaymentStatus(PaymentStatus.PENDING);
        }

        return registrationRepository.save(registration);
    }
```

(`import org.confcms.cms.domain.Conference;`, `import org.confcms.cms.domain.PaymentProvider;`, `import org.confcms.cms.service.FileStorageService;`, `import org.springframework.web.multipart.MultipartFile;` added.) The double-submission guard (`findByUserIdAndConferenceIdAndPaymentStatusNot(..., FAILED)`) deliberately excludes `FAILED` registrations, since a rejected registration is exactly the case that should be re-openable via re-upload (Section 5.3), not a blocker to trying again.

### 4.5 `RegistrationController` changes

Modify `src/main/java/org/confcms/cms/registration/web/controller/RegistrationController.java`:

```java
    @PostMapping("/register")
    public String processRegistration(@RequestParam String ticketType,
                                      @RequestParam(value = "bankSlip", required = false) MultipartFile bankSlip,
                                      @AuthenticationPrincipal UserDetails userDetails,
                                      Model model) {

        if (userDetails == null) {
            return "redirect:/login";
        }

        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Conference conference = conferenceService.getActiveConference();

        try {
            registrationService.register(user, conference, ticketType, bankSlip);
            return "redirect:/dashboard?registered=true";
        } catch (IllegalArgumentException iae) {
            model.addAttribute("conference", conference);
            model.addAttribute("paymentConfig", conference.getPaymentConfig());
            model.addAttribute("user", user);
            model.addAttribute("error", iae.getMessage());
            return "public/register";
        }
    }
```

`bankSlip` is `required = false` at the Spring MVC binding layer (so FREE/STRIPE/PAYPAL submissions with no file input present don't fail parameter binding); `validateBankSlip`'s own null/empty check inside `register()` is what actually enforces "required when LOCAL_BANK," matching this codebase's established pattern of validating business rules in the service layer, not the controller.

### 4.6 `register.html` changes

Confirmed against the live template (`src/main/resources/templates/public/register.html`, lines 39-73): the form tag is already `<form th:action="@{/register}" method="post">` (line 39) — no CSRF fix needed here, that was already handled by an earlier feature this session. Two changes:

Modify line 39 to add multipart support (required for any `<input type="file">` to transmit file content; harmless to add for the FREE/STRIPE/PAYPAL branches that don't use it):

```html
                            <form th:action="@{/register}" method="post" enctype="multipart/form-data">
```

Add an error block once, immediately after the `<hr>` at line 37 and before the `<form>` tag (so it renders regardless of which provider branch is active when a validation error redisplays the form):

```html
                            <div th:if="${error}" class="alert alert-danger" th:text="${error}"></div>
```

Inside the `LOCAL_BANK` div (lines 55-60, immediately after the existing `<small>` line, before that div's closing `</div>`):

```html
                                    <div class="mb-3 mt-2">
                                        <label class="form-label">Upload Bank Slip (PDF, JPEG, or PNG)</label>
                                        <input type="file" class="form-control" name="bankSlip" accept=".pdf,.jpg,.jpeg,.png">
                                    </div>
```

## 5. Admin verification

### 5.1 `AdminRegistrationController`

New file `src/main/java/org/confcms/cms/web/controller/AdminRegistrationController.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.domain.Registration;
import org.confcms.cms.registration.repository.RegistrationRepository;
import org.confcms.cms.registration.service.RegistrationService;
import org.confcms.cms.service.ConferenceService;
import org.confcms.cms.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;

@Controller
@RequestMapping("/admin/registrations")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminRegistrationController {

    private final RegistrationRepository registrationRepository;
    private final RegistrationService registrationService;
    private final ConferenceService conferenceService;
    private final FileStorageService fileStorageService;

    @GetMapping
    public String list(Model model) {
        var conference = conferenceService.getActiveConference();
        model.addAttribute("registrations", registrationRepository
                .findByConferenceIdAndPaymentStatus(conference.getId(), PaymentStatus.AWAITING_VERIFICATION));
        return "admin/registrations";
    }

    @GetMapping("/{id}/slip")
    public ResponseEntity<?> viewSlip(@PathVariable Long id) {
        Registration registration = registrationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Registration not found"));
        if (registration.getBankSlipPath() == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            Path path = fileStorageService.load(registration.getBankSlipPath());
            Resource resource = new UrlResource(path.toUri());
            return ResponseEntity.ok()
                    .contentType(contentTypeFor(path))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + registration.getBankSlipOriginalFilename() + "\"")
                    .body(resource);
        } catch (MalformedURLException e) {
            return ResponseEntity.status(404).body("File not found");
        }
    }

    private MediaType contentTypeFor(Path path) {
        try {
            byte[] header = Files.readAllBytes(path);
            if (header.length >= 3 && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8) {
                return MediaType.IMAGE_JPEG;
            }
            if (header.length >= 4 && header[0] == (byte) 0x89 && header[1] == 'P') {
                return MediaType.IMAGE_PNG;
            }
            return MediaType.APPLICATION_PDF;
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id) {
        registrationService.markAsPaid(id);
        return "redirect:/admin/registrations";
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id, @RequestParam(required = false) String reason) {
        registrationService.reject(id, reason);
        return "redirect:/admin/registrations";
    }
}
```

`contentTypeFor` re-sniffs the stored file's magic bytes rather than trusting a stored content-type string (none is stored — `MultipartFile.getContentType()` is client-supplied and not trusted for this purpose, consistent with `validateBankSlip` re-checking bytes rather than relying on the upload's declared type).

### 5.2 `RegistrationService.reject`

Add to `RegistrationService`:

```java
    @Transactional
    public void reject(Long registrationId, String reason) {
        Registration registration = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new IllegalArgumentException("Registration not found"));
        registration.setPaymentStatus(PaymentStatus.FAILED);
        registration.setRejectionReason(reason == null || reason.isBlank() ? "No reason provided" : reason);
        registrationRepository.save(registration);
    }
```

`markAsPaid` (Section 1's research confirmed this already exists, unused) needs one small addition — clearing any stale rejection reason on approval, in case this registration was previously rejected and re-uploaded:

```java
    @Transactional
    public void markAsPaid(Long registrationId) {
        Registration registration = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new IllegalArgumentException("Registration not found"));
        registration.setPaymentStatus(PaymentStatus.PAID);
        registration.setRejectionReason(null);
        registrationRepository.save(registration);
    }
```

### 5.3 Re-upload after rejection

New endpoints on `RegistrationController`:

```java
    @GetMapping("/registration/{id}/reupload-slip")
    public String showReuploadForm(@PathVariable Long id, Model model, @AuthenticationPrincipal UserDetails userDetails) {
        Registration registration = ownedRejectedRegistration(id, userDetails);
        model.addAttribute("registration", registration);
        return "reupload_slip";
    }

    @PostMapping("/registration/{id}/reupload-slip")
    public String reuploadSlip(@PathVariable Long id, @RequestParam MultipartFile bankSlip,
                                Model model, @AuthenticationPrincipal UserDetails userDetails) {
        Registration registration = ownedRejectedRegistration(id, userDetails);
        try {
            registrationService.reuploadSlip(registration, bankSlip);
            return "redirect:/dashboard?slipResubmitted=true";
        } catch (IllegalArgumentException iae) {
            model.addAttribute("registration", registration);
            model.addAttribute("error", iae.getMessage());
            return "reupload_slip";
        }
    }

    private Registration ownedRejectedRegistration(Long id, UserDetails userDetails) {
        if (userDetails == null) {
            throw new IllegalArgumentException("Not authenticated");
        }
        User user = userRepository.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        Registration registration = registrationService.getRegistration(id);
        if (!registration.getUser().getId().equals(user.getId())) {
            throw new SecurityException("Not your registration");
        }
        if (registration.getPaymentStatus() != PaymentStatus.FAILED) {
            throw new IllegalArgumentException("This registration is not awaiting a re-upload");
        }
        return registration;
    }
```

`RegistrationService` gains the two small supporting methods this controller calls:

```java
    @Transactional(readOnly = true)
    public Registration getRegistration(Long id) {
        return registrationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Registration not found"));
    }

    @Transactional
    public void reuploadSlip(Registration registration, MultipartFile bankSlip) {
        validateBankSlip(bankSlip);
        String filePath = fileStorageService.store(bankSlip);
        registration.setBankSlipPath(filePath);
        registration.setBankSlipOriginalFilename(bankSlip.getOriginalFilename());
        registration.setPaymentStatus(PaymentStatus.AWAITING_VERIFICATION);
        registration.setRejectionReason(null);
        registrationRepository.save(registration);
    }
```

New minimal template `src/main/resources/templates/reupload_slip.html` (same Bootstrap card shape as `dashboard.html`/`account_settings.html`): shows `registration.rejectionReason`, a file input, submit button posting to `th:action="@{/registration/{id}/reupload-slip(id=${registration.id})}"` with `enctype="multipart/form-data"`.

### 5.4 Dashboard surfaces the re-upload link

Modify `src/main/java/org/confcms/cms/web/controller/DashboardController.java`'s `dashboard()` method to add:

```java
        registrationRepository.findByUserId(user.getId()).stream()
                .filter(r -> r.getPaymentStatus() == PaymentStatus.FAILED && r.getRejectionReason() != null)
                .findFirst()
                .ifPresent(r -> model.addAttribute("rejectedRegistration", r));
```

(`RegistrationRepository` and `PaymentStatus` imports added; `DashboardController` gains a `private final RegistrationRepository registrationRepository` field, which `@RequiredArgsConstructor` adds to the generated constructor as its new second parameter — `DashboardControllerTest`'s existing `new DashboardController(userRepository)` call in `setUp()` must be updated to `new DashboardController(userRepository, registrationRepository)`, with a new `@Mock RegistrationRepository registrationRepository` field alongside the existing `@Mock UserRepository userRepository`, and every existing test in that file needs `when(registrationRepository.findByUserId(any())).thenReturn(List.of())` stubbed for the non-admin-role test cases that now reach this new lookup — or, cheaper, stub it once in `setUp()` since none of the existing three tests care about its result.) `dashboard.html` gains, alongside the existing password-prompt banner:

```html
        <div th:if="${rejectedRegistration}" class="alert alert-danger">
            Your bank slip was rejected: <span th:text="${rejectedRegistration.rejectionReason}"></span>.
            <a th:href="@{/registration/{id}/reupload-slip(id=${rejectedRegistration.id})}">Upload a corrected slip</a>
        </div>
```

### 5.5 `admin/registrations.html`

New template, same structural shape as `admin/dashboard.html`'s papers table:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head>
    <meta charset="UTF-8">
    <title>Registrations - Conference CMS</title>
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
        <h2>Registrations Awaiting Verification</h2>
        <table class="table table-striped">
            <thead>
                <tr>
                    <th>Registrant</th>
                    <th>Ticket Type</th>
                    <th>Amount</th>
                    <th>Slip</th>
                    <th>Actions</th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="reg : ${registrations}">
                    <td th:text="${reg.user.fullName} + ' (' + ${reg.user.email} + ')'"></td>
                    <td th:text="${reg.ticketType}"></td>
                    <td th:text="${reg.amount}"></td>
                    <td>
                        <a th:href="@{/admin/registrations/{id}/slip(id=${reg.id})}" target="_blank">View slip</a>
                    </td>
                    <td>
                        <form method="post" th:action="@{/admin/registrations/{id}/approve(id=${reg.id})}" class="d-inline">
                            <button type="submit" class="btn btn-sm btn-success">Approve</button>
                        </form>
                        <form method="post" th:action="@{/admin/registrations/{id}/reject(id=${reg.id})}" class="d-inline">
                            <input type="text" name="reason" placeholder="Reason (optional)" class="form-control form-control-sm d-inline w-auto">
                            <button type="submit" class="btn btn-sm btn-danger">Reject</button>
                        </form>
                    </td>
                </tr>
                <tr th:if="${registrations.isEmpty()}">
                    <td colspan="5" class="text-muted">No registrations awaiting verification.</td>
                </tr>
            </tbody>
        </table>
    </div>
</body>

</html>
```

`admin/dashboard.html` gains a nav link to this new page (one line, alongside its existing content, exact placement decided at implementation time based on the current nav structure).

## 6. Error handling & edge cases

- **`VIRTUAL` ticket-type crash** — fixed by Section 4.4's `TICKET_PRICES` addition.
- **Missing bank slip on a LOCAL_BANK submission** — `validateBankSlip`'s null/empty check throws `IllegalArgumentException`, caught by `RegistrationController`, form re-rendered with the error (Section 4.5).
- **Wrong file type / corrupted upload** — same exception path, message names the accepted types.
- **Double-submission** — `findByUserIdAndConferenceIdAndPaymentStatusNot(..., FAILED)` guard (Section 4.4) rejects a second attempt while one is `PENDING`/`AWAITING_VERIFICATION`/`PAID`, but explicitly allows retrying after `FAILED` — though in practice a `FAILED` registration is expected to go through the dedicated re-upload flow (Section 5.3) rather than a fresh `/register` POST; both paths converging on the same non-blocked state is intentional redundancy, not a designed dual path.
- **Reject with no reason** — defaults to `"No reason provided"` (Section 5.2), never a blank string shown to the registrant.
- **Encryption key rotation** — out of scope, documented limitation (Section 2.4's note extends to this: no tooling exists to re-encrypt under a new key).
- **Concurrent admin approve/reject** — no additional locking; `@Transactional` per-method matches every other service in this codebase (e.g. `PasswordResetService`), a double-click race is a no-op re-write, not a correctness or security issue.
- **`ConferencePaymentConfig` decrypt failure on legacy data** — documented in Section 2.4; not exercised by any seed data, so no migration path is built.
- **Admin views a slip for a since-approved/rejected registration** — `viewSlip` has no status check; an admin can always re-view a slip regardless of current status (useful for auditing a past decision), matching how paper review downloads aren't restricted by review-completion state either.

## 7. Testing approach

Consistent with this codebase's established convention (pure Mockito unit tests, no `@SpringBootTest`/`MockMvc`):

- `PaymentSecretConverter`: encrypt-then-decrypt round-trip returns the original plaintext; two encryptions of the same plaintext produce different ciphertext (confirms IV randomization); null in → null out both directions; a bad key length throws `IllegalStateException` at construction.
- `RegistrationService.register`: existing tests extended for the new signature — LOCAL_BANK with a valid slip sets `AWAITING_VERIFICATION` and stores the file (mocked `FileStorageService`); LOCAL_BANK with no slip throws; non-LOCAL_BANK ignores any slip and sets `PENDING`; duplicate active registration throws; `VIRTUAL` ticket type resolves to `25`.
- `RegistrationService.reject`/`markAsPaid`/`reuploadSlip`/`getRegistration`: straightforward Mockito tests on each, including `reject` defaulting a blank/null reason and `markAsPaid` clearing a stale `rejectionReason`.
- `AdminRegistrationController`: `list` returns the filtered registrations; `viewSlip` returns 404 for a registration with no slip, correct content-type for each of PDF/JPEG/PNG (via three small on-disk fixture files created in a `@TempDir`); `approve`/`reject` delegate to the service and redirect.
- `RegistrationController`: extended for the new `bankSlip` parameter and the re-upload endpoints — `ownedRejectedRegistration`'s three guard branches (not authenticated, not the owner, not `FAILED`) each get a dedicated test.
- `DashboardController`: extended to assert `rejectedRegistration` is present/absent correctly.
- Manual boot verification: the actual encryption round-trip against a real (dev) H2 boot, and the four template changes (curl-based, consistent with this session's established manual-verification pattern for anything template-rendering that isn't meaningfully unit-testable).

## 8. Deferred / explicitly out of scope

- Real Stripe Checkout / Payment Intents integration.
- Real PayPal Orders API integration.
- Encryption-key rotation tooling.
- Editing an existing conference's payment config (`AdminConferenceController` create-only gap, predates this spec).
- Per-conference ticket pricing configuration (`TICKET_PRICES` stays a hardcoded map — already flagged as a `ponytail:` comment in the existing code, not reopened here beyond adding the one missing entry).
- Slip versioning/history (single-field overwrite-on-reupload is the deliberate design, Section 4.2).
