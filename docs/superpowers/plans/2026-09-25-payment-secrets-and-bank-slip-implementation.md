# Payment Secrets Encryption & Bank-Slip Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Encrypt the Stripe/PayPal secret columns on `ConferencePaymentConfig` at rest, and build the complete LOCAL_BANK bank-slip upload → admin verification workflow that doesn't exist today.

**Architecture:** A Spring-managed JPA `AttributeConverter` (`PaymentSecretConverter`) transparently AES-GCM-encrypts the two secret fields on write and decrypts on read, keyed by a required env var — no change to the existing admin conference-creation form. `Registration` gains a `Conference` FK (a real gap found during spec-writing) plus bank-slip fields and a new `AWAITING_VERIFICATION` status. The registrant uploads a slip in the same `/register` POST as today's ticket selection; a new `AdminRegistrationController` lists pending registrations, streams the slip back for viewing, and lets an admin approve (wires up the existing but unused `RegistrationService.markAsPaid`) or reject (new `reject` method) with a reason. A rejected registrant sees the reason on their dashboard and can re-upload via a new small endpoint pair, resetting status to `AWAITING_VERIFICATION`.

**Tech Stack:** Spring Boot 3.5.8, Spring Data JPA, `javax.crypto` (JDK built-in AES/GCM, no new dependency), Thymeleaf, Lombok, JUnit 5 + Mockito + AssertJ (this codebase's only test style — no `@SpringBootTest`/`MockMvc` anywhere, and this plan does not introduce any).

**Global Constraints** (binding on every task, from `docs/superpowers/specs/2026-09-25-payment-secrets-and-bank-slip-design.md`):
- No `@SpringBootTest`, no `MockMvc`, no new test frameworks — pure Mockito unit tests, `@ExtendWith(MockitoExtension.class)`, AssertJ assertions.
- `PaymentSecretConverter` lives in `org.confcms.cms.domain`, co-located with `ConferencePaymentConfig` — matches this codebase's convention of small support classes living next to the entity they serve.
- `AdminRegistrationController` lives in `org.confcms.cms.web.controller`, matching `AdminUserController`/`AccountSettingsController`'s existing location.
- No changes to `AdminConferenceController` or `admin/conference_form.html` — the encryption converter is transparent at the JPA layer.
- `bankSlipPath`/`bankSlipOriginalFilename`/`rejectionReason` are single overwritable fields on `Registration`, not a versioned child entity — no slip history is kept.
- Every file-upload validation re-sniffs magic bytes from the actual file content; never trusts `MultipartFile.getContentType()` (client-supplied, untrusted), matching `SubmissionService.validatePdf`'s existing precedent.
- `ddl-auto=update` (default profile) does not drop columns; this plan only adds columns, no destructive schema change.

---

### Task 1: `PaymentSecretConverter` — AES-GCM encryption for payment secrets

**Files:**
- Create: `src/main/java/org/confcms/cms/domain/PaymentSecretConverter.java`
- Test: `src/test/java/org/confcms/cms/domain/PaymentSecretConverterTest.java`
- Modify: `src/main/resources/application.properties`
- Modify: `src/main/resources/application-dev.properties`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/confcms/cms/domain/PaymentSecretConverterTest.java`:

```java
package org.confcms.cms.domain;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentSecretConverterTest {

    private static final String VALID_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes()); // 33 chars -> trimmed to 32 bytes below

    private static String validKey() {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            keyBytes[i] = (byte) i;
        }
        return Base64.getEncoder().encodeToString(keyBytes);
    }

    @Test
    void encryptsAndDecryptsRoundTrip() {
        PaymentSecretConverter converter = new PaymentSecretConverter(validKey());

        String ciphertext = converter.convertToDatabaseColumn("sk_test_secret123");
        String plaintext = converter.convertToEntityAttribute(ciphertext);

        assertThat(ciphertext).isNotEqualTo("sk_test_secret123");
        assertThat(plaintext).isEqualTo("sk_test_secret123");
    }

    @Test
    void encryptingTheSamePlaintextTwiceProducesDifferentCiphertext() {
        PaymentSecretConverter converter = new PaymentSecretConverter(validKey());

        String first = converter.convertToDatabaseColumn("same-secret");
        String second = converter.convertToDatabaseColumn("same-secret");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void nullPlaintextConvertsToNull() {
        PaymentSecretConverter converter = new PaymentSecretConverter(validKey());

        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void nullStoredValueConvertsToNull() {
        PaymentSecretConverter converter = new PaymentSecretConverter(validKey());

        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void rejectsAKeyThatIsNotExactly32BytesAfterDecoding() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());

        assertThatThrownBy(() -> new PaymentSecretConverter(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "org.confcms.cms.domain.PaymentSecretConverterTest"`
Expected: FAIL — compile error, `PaymentSecretConverter` does not exist yet.

- [ ] **Step 3: Create `PaymentSecretConverter`**

Create `src/main/java/org/confcms/cms/domain/PaymentSecretConverter.java`:

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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "org.confcms.cms.domain.PaymentSecretConverterTest"`
Expected: PASS, 5 tests. (Note: the unused `VALID_KEY` constant at the top of the test file is dead — remove it, the `validKey()` helper method is what's actually used. Fix this before running if you notice it; it's a leftover from drafting and would otherwise trigger an "unused field" warning.)

- [ ] **Step 5: Add the required property to `application.properties`**

Modify `src/main/resources/application.properties` — add after the `spring.jpa.properties.hibernate.format_sql=true` line (in the Database section):

```properties

# Required: base64-encoded 32-byte AES-256 key used to encrypt Stripe/PayPal secret
# columns on conference_payment_configs at rest. Generate with:
#   openssl rand -base64 32
# There is no default -- the app refuses to start without it, since a payment
# secret written without this key would be either unencrypted or unrecoverable.
app.secrets.encryption-key=${PAYMENT_SECRETS_KEY}
```

- [ ] **Step 6: Add a fixed dev-only key to `application-dev.properties`**

First generate a real 32-byte key and its base64 encoding:

Run: `openssl rand -base64 32`

Take the output (a base64 string) and add it to `src/main/resources/application-dev.properties`, in the Logging section or nearby:

```properties

# Fixed dev-only encryption key -- never use this value outside local development.
# Generated once via `openssl rand -base64 32`; H2 is in-memory and reset every
# boot, so key stability across restarts doesn't matter here.
app.secrets.encryption-key=<paste the openssl output here>
```

- [ ] **Step 7: Boot the dev profile to confirm the app still starts**

Run: `./gradlew bootRun` (with `dev` profile active via `SPRING_PROFILES_ACTIVE=dev` or however this project's dev profile is normally activated — check `application.properties` for the current default before assuming).
Expected: `Started ConferenceCmsApplication` with no `PaymentSecretConverter`-related startup failure. Stop the app once confirmed (Ctrl+C or kill the process).

- [ ] **Step 8: Remove the dead `VALID_KEY` constant from the test (cleanup)**

Modify `src/test/java/org/confcms/cms/domain/PaymentSecretConverterTest.java` — delete these two lines near the top of the class:

```java
    private static final String VALID_KEY = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes()); // 33 chars -> trimmed to 32 bytes below

```

Run: `./gradlew test --tests "org.confcms.cms.domain.PaymentSecretConverterTest"`
Expected: PASS, 5 tests (unchanged pass count, just removes dead code).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/confcms/cms/domain/PaymentSecretConverter.java src/test/java/org/confcms/cms/domain/PaymentSecretConverterTest.java src/main/resources/application.properties src/main/resources/application-dev.properties
git commit -m "feat: add AES-GCM converter for encrypting payment secrets at rest"
```

---

### Task 2: Wire the converter into `ConferencePaymentConfig`

**Files:**
- Modify: `src/main/java/org/confcms/cms/domain/ConferencePaymentConfig.java`

- [ ] **Step 1: Add `@Convert` to the two secret fields**

Modify `src/main/java/org/confcms/cms/domain/ConferencePaymentConfig.java` — replace:

```java
    // Stripe
    private String stripePublishableKey;
    private String stripeSecretKey;

    // PayPal
    private String paypalClientId;
    private String paypalClientSecret;
```

with:

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

(`import jakarta.persistence.Convert;` is already covered by the existing `import jakarta.persistence.*;` wildcard at the top of this file — confirm this before assuming; if the file uses explicit imports instead of a wildcard, add `import jakarta.persistence.Convert;` explicitly.)

- [ ] **Step 2: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Boot the dev profile and manually verify encryption round-trips through a real save/load**

Run: `./gradlew bootRun` with the dev profile active. In a browser, log in as the dev admin and create a new conference (`/admin/conference/new`) with payment provider set to Stripe, filling in a test `stripeSecretKey` value (e.g. `sk_test_manual_verification_12345`). After saving, use the H2 console (`/h2-console`, confirm JDBC URL from `application-dev.properties`) to inspect the `conference_payment_configs` table directly — confirm the `stripe_secret_key` column contains base64 ciphertext, not the plaintext value you typed. Then reload `/admin/conference/new` is not the right page to re-view it (no edit page exists, per the spec's noted out-of-scope gap) — instead, confirm the app didn't throw any decryption error by checking the server log for `IllegalStateException` mentioning "decrypt" during that boot session. Stop the app once verified.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/confcms/cms/domain/ConferencePaymentConfig.java
git commit -m "feat: encrypt Stripe/PayPal secret columns via PaymentSecretConverter"
```

---

### Task 3: `Registration` gains a `Conference` reference, bank-slip fields, and `AWAITING_VERIFICATION` status

**Files:**
- Modify: `src/main/java/org/confcms/cms/registration/domain/PaymentStatus.java`
- Modify: `src/main/java/org/confcms/cms/registration/domain/Registration.java`
- Modify: `src/main/java/org/confcms/cms/registration/repository/RegistrationRepository.java`

This task is pure entity/repository schema change with no business logic — no dedicated unit test applies (entities and derived-query repository interfaces have no existing test precedent in this codebase, confirmed by this session's prior plans following the same convention for `UserIdentity` and similar additions).

- [ ] **Step 1: Add `AWAITING_VERIFICATION` to `PaymentStatus`**

Modify `src/main/java/org/confcms/cms/registration/domain/PaymentStatus.java` — replace the full file:

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

- [ ] **Step 2: Add the new fields to `Registration`**

Modify `src/main/java/org/confcms/cms/registration/domain/Registration.java` — replace the full file:

```java
package org.confcms.cms.registration.domain;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.core.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "registrations")
@Getter
@Setter
public class Registration extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;

    @Column(nullable = false)
    private String ticketType;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    private String invoicePath;
    private String bankSlipPath;
    private String bankSlipOriginalFilename;

    @Column(columnDefinition = "TEXT")
    private String rejectionReason;
}
```

- [ ] **Step 3: Add the two new repository query methods**

Modify `src/main/java/org/confcms/cms/registration/repository/RegistrationRepository.java` — replace the full file:

```java
package org.confcms.cms.registration.repository;

import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.domain.Registration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RegistrationRepository extends JpaRepository<Registration, Long> {
    List<Registration> findByUserId(Long userId);
    List<Registration> findByConferenceIdAndPaymentStatus(Long conferenceId, PaymentStatus paymentStatus);
    Optional<Registration> findByUserIdAndConferenceIdAndPaymentStatusNot(Long userId, Long conferenceId, PaymentStatus paymentStatus);
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/registration/domain/PaymentStatus.java src/main/java/org/confcms/cms/registration/domain/Registration.java src/main/java/org/confcms/cms/registration/repository/RegistrationRepository.java
git commit -m "feat: add Conference FK, bank-slip fields, and AWAITING_VERIFICATION status to Registration"
```

---

### Task 4: `RegistrationService` — bank-slip validation, updated `register`, `reject`, `getRegistration`, `reuploadSlip`

**Files:**
- Modify: `src/main/java/org/confcms/cms/registration/service/RegistrationService.java`
- Test: Create `src/test/java/org/confcms/cms/registration/service/RegistrationServiceTest.java` (no existing test file for this service, confirmed)

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/confcms/cms/registration/service/RegistrationServiceTest.java`:

```java
package org.confcms.cms.registration.service;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.domain.Registration;
import org.confcms.cms.registration.repository.RegistrationRepository;
import org.confcms.cms.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock
    private RegistrationRepository registrationRepository;
    @Mock
    private FileStorageService fileStorageService;

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationService(registrationRepository, fileStorageService);
    }

    private User user(long id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private Conference conferenceWithProvider(long id, org.confcms.cms.domain.PaymentProvider provider) {
        Conference c = new Conference();
        c.setId(id);
        if (provider != null) {
            org.confcms.cms.domain.ConferencePaymentConfig config = new org.confcms.cms.domain.ConferencePaymentConfig();
            config.setProvider(provider);
            c.setPaymentConfig(config);
        }
        return c;
    }

    @Test
    void registerWithFreeProviderSetsPendingAndIgnoresSlip() {
        User u = user(1L);
        Conference c = conferenceWithProvider(10L, org.confcms.cms.domain.PaymentProvider.FREE);
        when(registrationRepository.findByUserIdAndConferenceIdAndPaymentStatusNot(1L, 10L, PaymentStatus.FAILED))
                .thenReturn(Optional.empty());
        when(registrationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Registration result = service.register(u, c, "REGULAR", null);

        assertThat(result.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("100"));
        verify(fileStorageService, never()).store(any());
    }

    @Test
    void registerWithVirtualTicketTypeResolvesToTwentyFive() {
        User u = user(1L);
        Conference c = conferenceWithProvider(10L, org.confcms.cms.domain.PaymentProvider.FREE);
        when(registrationRepository.findByUserIdAndConferenceIdAndPaymentStatusNot(1L, 10L, PaymentStatus.FAILED))
                .thenReturn(Optional.empty());
        when(registrationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Registration result = service.register(u, c, "VIRTUAL", null);

        assertThat(result.getAmount()).isEqualByComparingTo(new BigDecimal("25"));
    }

    @Test
    void registerWithUnknownTicketTypeThrows() {
        User u = user(1L);
        Conference c = conferenceWithProvider(10L, org.confcms.cms.domain.PaymentProvider.FREE);
        when(registrationRepository.findByUserIdAndConferenceIdAndPaymentStatusNot(1L, 10L, PaymentStatus.FAILED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(u, c, "PLATINUM", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown ticket type");
    }

    @Test
    void registerWithLocalBankAndValidSlipSetsAwaitingVerification() {
        User u = user(1L);
        Conference c = conferenceWithProvider(10L, org.confcms.cms.domain.PaymentProvider.LOCAL_BANK);
        MultipartFile slip = new MockMultipartFile("bankSlip", "slip.pdf", "application/pdf",
                "%PDF-1.4 fake content".getBytes());
        when(registrationRepository.findByUserIdAndConferenceIdAndPaymentStatusNot(1L, 10L, PaymentStatus.FAILED))
                .thenReturn(Optional.empty());
        when(fileStorageService.store(slip)).thenReturn("/uploads/uuid_slip.pdf");
        when(registrationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Registration result = service.register(u, c, "REGULAR", slip);

        assertThat(result.getPaymentStatus()).isEqualTo(PaymentStatus.AWAITING_VERIFICATION);
        assertThat(result.getBankSlipPath()).isEqualTo("/uploads/uuid_slip.pdf");
        assertThat(result.getBankSlipOriginalFilename()).isEqualTo("slip.pdf");
    }

    @Test
    void registerWithLocalBankAndNoSlipThrows() {
        User u = user(1L);
        Conference c = conferenceWithProvider(10L, org.confcms.cms.domain.PaymentProvider.LOCAL_BANK);
        when(registrationRepository.findByUserIdAndConferenceIdAndPaymentStatusNot(1L, 10L, PaymentStatus.FAILED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(u, c, "REGULAR", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank slip");

        verify(registrationRepository, never()).save(any());
    }

    @Test
    void registerWithLocalBankAndNonPdfNonImageSlipThrows() {
        User u = user(1L);
        Conference c = conferenceWithProvider(10L, org.confcms.cms.domain.PaymentProvider.LOCAL_BANK);
        MultipartFile badFile = new MockMultipartFile("bankSlip", "slip.txt", "text/plain",
                "just some text".getBytes());
        when(registrationRepository.findByUserIdAndConferenceIdAndPaymentStatusNot(1L, 10L, PaymentStatus.FAILED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.register(u, c, "REGULAR", badFile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PDF, JPEG, or PNG");
    }

    @Test
    void registerRejectsWhenAnActiveRegistrationAlreadyExists() {
        User u = user(1L);
        Conference c = conferenceWithProvider(10L, org.confcms.cms.domain.PaymentProvider.FREE);
        when(registrationRepository.findByUserIdAndConferenceIdAndPaymentStatusNot(1L, 10L, PaymentStatus.FAILED))
                .thenReturn(Optional.of(new Registration()));

        assertThatThrownBy(() -> service.register(u, c, "REGULAR", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already have a registration");

        verify(registrationRepository, never()).save(any());
    }

    @Test
    void markAsPaidSetsPaidAndClearsRejectionReason() {
        Registration reg = new Registration();
        reg.setPaymentStatus(PaymentStatus.AWAITING_VERIFICATION);
        reg.setRejectionReason("stale reason from a prior rejection");
        when(registrationRepository.findById(5L)).thenReturn(Optional.of(reg));
        when(registrationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.markAsPaid(5L);

        assertThat(reg.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(reg.getRejectionReason()).isNull();
    }

    @Test
    void rejectSetsFailedAndStoresReason() {
        Registration reg = new Registration();
        reg.setPaymentStatus(PaymentStatus.AWAITING_VERIFICATION);
        when(registrationRepository.findById(5L)).thenReturn(Optional.of(reg));
        when(registrationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reject(5L, "Amount doesn't match ticket price");

        assertThat(reg.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reg.getRejectionReason()).isEqualTo("Amount doesn't match ticket price");
    }

    @Test
    void rejectWithBlankReasonDefaultsToNoReasonProvided() {
        Registration reg = new Registration();
        when(registrationRepository.findById(5L)).thenReturn(Optional.of(reg));
        when(registrationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reject(5L, "   ");

        assertThat(reg.getRejectionReason()).isEqualTo("No reason provided");
    }

    @Test
    void rejectWithNullReasonDefaultsToNoReasonProvided() {
        Registration reg = new Registration();
        when(registrationRepository.findById(5L)).thenReturn(Optional.of(reg));
        when(registrationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reject(5L, null);

        assertThat(reg.getRejectionReason()).isEqualTo("No reason provided");
    }

    @Test
    void getRegistrationReturnsItWhenFound() {
        Registration reg = new Registration();
        when(registrationRepository.findById(7L)).thenReturn(Optional.of(reg));

        assertThat(service.getRegistration(7L)).isEqualTo(reg);
    }

    @Test
    void getRegistrationThrowsWhenNotFound() {
        when(registrationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRegistration(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reuploadSlipStoresNewFileResetsStatusAndClearsRejectionReason() {
        Registration reg = new Registration();
        reg.setPaymentStatus(PaymentStatus.FAILED);
        reg.setRejectionReason("Illegible scan");
        MultipartFile slip = new MockMultipartFile("bankSlip", "corrected.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
        when(fileStorageService.store(slip)).thenReturn("/uploads/uuid_corrected.jpg");
        when(registrationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reuploadSlip(reg, slip);

        assertThat(reg.getPaymentStatus()).isEqualTo(PaymentStatus.AWAITING_VERIFICATION);
        assertThat(reg.getBankSlipPath()).isEqualTo("/uploads/uuid_corrected.jpg");
        assertThat(reg.getBankSlipOriginalFilename()).isEqualTo("corrected.jpg");
        assertThat(reg.getRejectionReason()).isNull();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.registration.service.RegistrationServiceTest"`
Expected: FAIL — compile errors (`register` signature mismatch, `reject`/`getRegistration`/`reuploadSlip` don't exist, constructor doesn't take `FileStorageService`).

- [ ] **Step 3: Rewrite `RegistrationService`**

Modify `src/main/java/org/confcms/cms/registration/service/RegistrationService.java` — replace the full file:

```java
package org.confcms.cms.registration.service;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.PaymentProvider;
import org.confcms.cms.domain.User;
import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.domain.Registration;
import org.confcms.cms.registration.repository.RegistrationRepository;
import org.confcms.cms.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RegistrationService {

    // ponytail: hardcoded ticket prices, move to per-conference config when
    // conference-level ticket pricing is designed as its own feature.
    private static final Map<String, BigDecimal> TICKET_PRICES = Map.of(
            "REGULAR", new BigDecimal("100"),
            "STUDENT", new BigDecimal("50"),
            "VIRTUAL", new BigDecimal("25")
    );

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G'};

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

    @Transactional
    public void markAsPaid(Long registrationId) {
        Registration registration = getRegistration(registrationId);
        registration.setPaymentStatus(PaymentStatus.PAID);
        registration.setRejectionReason(null);
        registrationRepository.save(registration);
    }

    @Transactional
    public void reject(Long registrationId, String reason) {
        Registration registration = getRegistration(registrationId);
        registration.setPaymentStatus(PaymentStatus.FAILED);
        registration.setRejectionReason(reason == null || reason.isBlank() ? "No reason provided" : reason);
        registrationRepository.save(registration);
    }

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
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.registration.service.RegistrationServiceTest"`
Expected: PASS, 16 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/confcms/cms/registration/service/RegistrationService.java src/test/java/org/confcms/cms/registration/service/RegistrationServiceTest.java
git commit -m "feat: bank-slip validation, reject/reupload flows, VIRTUAL ticket price"
```

---

### Task 5: `RegistrationController` — accept the bank slip on `/register`, add re-upload endpoints

**Files:**
- Modify: `src/main/java/org/confcms/cms/registration/web/controller/RegistrationController.java`
- Create: `src/main/resources/templates/reupload_slip.html`
- Test: Create `src/test/java/org/confcms/cms/registration/web/controller/RegistrationControllerTest.java` (no existing test file, confirmed)

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/confcms/cms/registration/web/controller/RegistrationControllerTest.java`:

```java
package org.confcms.cms.registration.web.controller;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.domain.Registration;
import org.confcms.cms.registration.service.RegistrationService;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.ConferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegistrationControllerTest {

    @Mock
    private ConferenceService conferenceService;
    @Mock
    private RegistrationService registrationService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserDetails userDetails;

    private RegistrationController controller;

    @BeforeEach
    void setUp() {
        controller = new RegistrationController(conferenceService, registrationService, userRepository);
    }

    private User user(long id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    @Test
    void processRegistrationRedirectsToLoginWhenNotAuthenticated() {
        String view = controller.processRegistration("REGULAR", null, null, new ExtendedModelMap());

        assertThat(view).isEqualTo("redirect:/login");
    }

    @Test
    void processRegistrationRedirectsToDashboardOnSuccess() {
        User u = user(1L, "author@example.com");
        Conference c = new Conference();
        when(userDetails.getUsername()).thenReturn("author@example.com");
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(u));
        when(conferenceService.getActiveConference()).thenReturn(c);
        when(registrationService.register(u, c, "REGULAR", null)).thenReturn(new Registration());

        String view = controller.processRegistration("REGULAR", null, userDetails, new ExtendedModelMap());

        assertThat(view).isEqualTo("redirect:/dashboard?registered=true");
    }

    @Test
    void processRegistrationRedisplaysFormWithErrorOnServiceRejection() {
        User u = user(1L, "author@example.com");
        Conference c = new Conference();
        when(userDetails.getUsername()).thenReturn("author@example.com");
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(u));
        when(conferenceService.getActiveConference()).thenReturn(c);
        when(registrationService.register(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("A bank slip file is required for bank transfer registrations"));

        Model model = new ExtendedModelMap();
        String view = controller.processRegistration("REGULAR", null, userDetails, model);

        assertThat(view).isEqualTo("public/register");
        assertThat(model.getAttribute("error")).isEqualTo("A bank slip file is required for bank transfer registrations");
    }

    @Test
    void reuploadSlipRejectsWhenNotTheOwningUser() {
        User owner = user(1L, "owner@example.com");
        User other = user(2L, "other@example.com");
        Registration reg = new Registration();
        reg.setUser(owner);
        reg.setPaymentStatus(PaymentStatus.FAILED);
        when(userDetails.getUsername()).thenReturn("other@example.com");
        when(userRepository.findByEmail("other@example.com")).thenReturn(Optional.of(other));
        when(registrationService.getRegistration(5L)).thenReturn(reg);
        MultipartFile slip = new MockMultipartFile("bankSlip", "x.pdf", "application/pdf", "%PDF-".getBytes());

        Model model = new ExtendedModelMap();
        assertThatCodeThrowsSecurityException(() -> controller.reuploadSlip(5L, slip, model, userDetails));
    }

    @Test
    void reuploadSlipRejectsWhenRegistrationIsNotFailed() {
        User owner = user(1L, "owner@example.com");
        Registration reg = new Registration();
        reg.setUser(owner);
        reg.setPaymentStatus(PaymentStatus.AWAITING_VERIFICATION);
        when(userDetails.getUsername()).thenReturn("owner@example.com");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(owner));
        when(registrationService.getRegistration(5L)).thenReturn(reg);
        MultipartFile slip = new MockMultipartFile("bankSlip", "x.pdf", "application/pdf", "%PDF-".getBytes());

        Model model = new ExtendedModelMap();
        try {
            controller.reuploadSlip(5L, slip, model, userDetails);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("not awaiting a re-upload");
        }
    }

    @Test
    void reuploadSlipSucceedsForTheOwningUserOnAFailedRegistration() {
        User owner = user(1L, "owner@example.com");
        Registration reg = new Registration();
        reg.setId(5L);
        reg.setUser(owner);
        reg.setPaymentStatus(PaymentStatus.FAILED);
        when(userDetails.getUsername()).thenReturn("owner@example.com");
        when(userRepository.findByEmail("owner@example.com")).thenReturn(Optional.of(owner));
        when(registrationService.getRegistration(5L)).thenReturn(reg);
        MultipartFile slip = new MockMultipartFile("bankSlip", "x.pdf", "application/pdf", "%PDF-".getBytes());

        String view = controller.reuploadSlip(5L, slip, new ExtendedModelMap(), userDetails);

        assertThat(view).isEqualTo("redirect:/dashboard?slipResubmitted=true");
    }

    private interface ThrowingRunnable {
        void run();
    }

    private void assertThatCodeThrowsSecurityException(ThrowingRunnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("expected SecurityException");
        } catch (SecurityException expected) {
            assertThat(expected.getMessage()).contains("Not your registration");
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.registration.web.controller.RegistrationControllerTest"`
Expected: FAIL — compile errors (`processRegistration` signature mismatch, `reuploadSlip`/`showReuploadForm` don't exist).

- [ ] **Step 3: Rewrite `RegistrationController`**

Modify `src/main/java/org/confcms/cms/registration/web/controller/RegistrationController.java` — replace the full file:

```java
package org.confcms.cms.registration.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.User;
import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.domain.Registration;
import org.confcms.cms.registration.service.RegistrationService;
import org.confcms.cms.service.ConferenceService;
import org.confcms.cms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@Controller
@RequiredArgsConstructor
public class RegistrationController {

    private final ConferenceService conferenceService;
    private final RegistrationService registrationService;
    private final UserRepository userRepository;

    @GetMapping("/register")
    public String showRegistrationPage(Model model, @AuthenticationPrincipal UserDetails userDetails) {
        Conference conference = conferenceService.getActiveConference();
        model.addAttribute("conference", conference);

        if (conference.getPaymentConfig() != null) {
            model.addAttribute("paymentConfig", conference.getPaymentConfig());
        }

        if (userDetails != null) {
            User user = userRepository.findByEmail(userDetails.getUsername()).orElse(null);
            model.addAttribute("user", user);
        }

        return "public/register";
    }

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
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.registration.web.controller.RegistrationControllerTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Create the re-upload template**

Create `src/main/resources/templates/reupload_slip.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">

<head>
    <meta charset="UTF-8">
    <title>Re-upload Bank Slip - Conference CMS</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css" rel="stylesheet">
    <link href="/css/app.css" rel="stylesheet">
</head>

<body class="bg-light">
    <nav class="navbar navbar-dark bg-dark">
        <div class="container">
            <a class="navbar-brand" href="/">Conference CMS</a>
            <a class="btn btn-outline-light" href="/logout">Logout</a>
        </div>
    </nav>

    <div class="container">
        <div class="row justify-content-center mt-5">
            <div class="col-md-6">
                <div class="card shadow">
                    <div class="card-body p-5">
                        <h2 class="mb-4">Re-upload Bank Slip</h2>

                        <div class="alert alert-warning">
                            Your previous slip was rejected: <span th:text="${registration.rejectionReason}"></span>
                        </div>
                        <div th:if="${error}" class="alert alert-danger" th:text="${error}"></div>

                        <form method="post"
                              th:action="@{/registration/{id}/reupload-slip(id=${registration.id})}"
                              enctype="multipart/form-data">
                            <div class="mb-3">
                                <label class="form-label">Corrected Bank Slip (PDF, JPEG, or PNG)</label>
                                <input type="file" class="form-control" name="bankSlip" accept=".pdf,.jpg,.jpeg,.png" required>
                            </div>
                            <button type="submit" class="btn btn-primary w-100">Submit</button>
                        </form>
                    </div>
                </div>
            </div>
        </div>
    </div>
</body>

</html>
```

- [ ] **Step 6: Compile the full project**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/registration/web/controller/RegistrationController.java src/main/resources/templates/reupload_slip.html src/test/java/org/confcms/cms/registration/web/controller/RegistrationControllerTest.java
git commit -m "feat: accept bank slip on /register, add re-upload endpoints"
```

---

### Task 6: `register.html` — file input, error display, multipart form

**Files:**
- Modify: `src/main/resources/templates/public/register.html`

Template-only change, no automated test — consistent with this codebase's established manual-verification pattern for template-rendering concerns (see prior CSRF-form-fix tasks this session).

- [ ] **Step 1: Add `enctype="multipart/form-data"` to the form tag**

Modify `src/main/resources/templates/public/register.html` line 39 — replace:

```html
                            <form th:action="@{/register}" method="post">
```

with:

```html
                            <form th:action="@{/register}" method="post" enctype="multipart/form-data">
```

- [ ] **Step 2: Add the error display block**

Modify `src/main/resources/templates/public/register.html` — insert immediately before the `<form ...>` line from Step 1 (i.e., right after the `<hr>` that precedes it):

```html
                            <div th:if="${error}" class="alert alert-danger" th:text="${error}"></div>
```

- [ ] **Step 3: Add the bank-slip file input**

Modify `src/main/resources/templates/public/register.html` — inside the `LOCAL_BANK` div, immediately after the existing `<small>Your registration will be pending until payment is verified.</small>` line and before that div's closing `</div>`, insert:

```html
                                        <div class="mb-3 mt-2">
                                            <label class="form-label">Upload Bank Slip (PDF, JPEG, or PNG)</label>
                                            <input type="file" class="form-control" name="bankSlip" accept=".pdf,.jpg,.jpeg,.png">
                                        </div>
```

- [ ] **Step 4: Boot the dev profile and manually verify**

Run: `./gradlew bootRun` with the dev profile active. In a browser: log in as a dev AUTHOR/REVIEWER account, visit `/register`. If the active dev conference's payment provider is LOCAL_BANK, confirm the file input renders under the bank details. Submit without a file — expect the red error box to reappear with "A bank slip file is required for bank transfer registrations". Submit with a valid PDF/JPEG/PNG — expect a redirect to `/dashboard?registered=true`. Stop the app once verified. (If the dev seed conference isn't LOCAL_BANK, this manual check is deferred to Task 9's end-to-end verification, which sets up the right conference state first — note this in your task report rather than skipping silently.)

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/public/register.html
git commit -m "feat: add bank-slip upload field to the registration form"
```

---

### Task 7: `AdminRegistrationController` — list, view slip, approve, reject

**Files:**
- Create: `src/main/java/org/confcms/cms/web/controller/AdminRegistrationController.java`
- Create: `src/main/resources/templates/admin/registrations.html`
- Modify: `src/main/resources/templates/admin/dashboard.html`
- Test: Create `src/test/java/org/confcms/cms/web/controller/AdminRegistrationControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/confcms/cms/web/controller/AdminRegistrationControllerTest.java`:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.domain.Registration;
import org.confcms.cms.registration.repository.RegistrationRepository;
import org.confcms.cms.registration.service.RegistrationService;
import org.confcms.cms.service.ConferenceService;
import org.confcms.cms.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminRegistrationControllerTest {

    @Mock
    private RegistrationRepository registrationRepository;
    @Mock
    private RegistrationService registrationService;
    @Mock
    private ConferenceService conferenceService;
    @Mock
    private FileStorageService fileStorageService;

    private AdminRegistrationController controller;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        controller = new AdminRegistrationController(registrationRepository, registrationService, conferenceService, fileStorageService);
    }

    @Test
    void listShowsAwaitingVerificationRegistrationsForTheActiveConference() {
        Conference conference = new Conference();
        conference.setId(10L);
        when(conferenceService.getActiveConference()).thenReturn(conference);
        List<Registration> pending = List.of(new Registration());
        when(registrationRepository.findByConferenceIdAndPaymentStatus(10L, PaymentStatus.AWAITING_VERIFICATION))
                .thenReturn(pending);

        Model model = new ExtendedModelMap();
        String view = controller.list(model);

        assertThat(view).isEqualTo("admin/registrations");
        assertThat(model.getAttribute("registrations")).isEqualTo(pending);
    }

    @Test
    void viewSlipReturns404WhenNoSlipStored() {
        Registration reg = new Registration();
        reg.setBankSlipPath(null);
        when(registrationRepository.findById(5L)).thenReturn(Optional.of(reg));

        ResponseEntity<?> response = controller.viewSlip(5L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void viewSlipReturnsPdfContentTypeForAPdfFile() throws Exception {
        Path pdfFile = tempDir.resolve("slip.pdf");
        Files.write(pdfFile, "%PDF-1.4 fake".getBytes());
        Registration reg = new Registration();
        reg.setBankSlipPath("slip.pdf");
        reg.setBankSlipOriginalFilename("slip.pdf");
        when(registrationRepository.findById(5L)).thenReturn(Optional.of(reg));
        when(fileStorageService.load("slip.pdf")).thenReturn(pdfFile);

        ResponseEntity<?> response = controller.viewSlip(5L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    }

    @Test
    void viewSlipReturnsJpegContentTypeForAJpegFile() throws Exception {
        Path jpegFile = tempDir.resolve("slip.jpg");
        Files.write(jpegFile, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
        Registration reg = new Registration();
        reg.setBankSlipPath("slip.jpg");
        reg.setBankSlipOriginalFilename("slip.jpg");
        when(registrationRepository.findById(5L)).thenReturn(Optional.of(reg));
        when(fileStorageService.load("slip.jpg")).thenReturn(jpegFile);

        ResponseEntity<?> response = controller.viewSlip(5L);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
    }

    @Test
    void viewSlipReturnsPngContentTypeForAPngFile() throws Exception {
        Path pngFile = tempDir.resolve("slip.png");
        Files.write(pngFile, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        Registration reg = new Registration();
        reg.setBankSlipPath("slip.png");
        reg.setBankSlipOriginalFilename("slip.png");
        when(registrationRepository.findById(5L)).thenReturn(Optional.of(reg));
        when(fileStorageService.load("slip.png")).thenReturn(pngFile);

        ResponseEntity<?> response = controller.viewSlip(5L);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
    }

    @Test
    void approveDelegatesToServiceAndRedirects() {
        String view = controller.approve(5L);

        verify(registrationService).markAsPaid(5L);
        assertThat(view).isEqualTo("redirect:/admin/registrations");
    }

    @Test
    void rejectDelegatesToServiceWithReasonAndRedirects() {
        String view = controller.reject(5L, "Amount mismatch");

        verify(registrationService).reject(5L, "Amount mismatch");
        assertThat(view).isEqualTo("redirect:/admin/registrations");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.AdminRegistrationControllerTest"`
Expected: FAIL — compile error, `AdminRegistrationController` does not exist yet.

- [ ] **Step 3: Create `AdminRegistrationController`**

Create `src/main/java/org/confcms/cms/web/controller/AdminRegistrationController.java`:

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

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.AdminRegistrationControllerTest"`
Expected: PASS, 7 tests.

- [ ] **Step 5: Create the admin registrations list template**

Create `src/main/resources/templates/admin/registrations.html`:

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

- [ ] **Step 6: Add a nav link from `admin/dashboard.html`**

Modify `src/main/resources/templates/admin/dashboard.html` line 13 — replace:

```html
            <a class="navbar-brand" href="#">CMS Admin</a>
```

with:

```html
            <a class="navbar-brand" href="/admin/dashboard">CMS Admin</a>
            <a class="btn btn-outline-light me-2" href="/admin/registrations">Registrations</a>
```

- [ ] **Step 7: Compile the full project**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/AdminRegistrationController.java src/main/resources/templates/admin/registrations.html src/main/resources/templates/admin/dashboard.html src/test/java/org/confcms/cms/web/controller/AdminRegistrationControllerTest.java
git commit -m "feat: add admin registration verification page (list, view slip, approve, reject)"
```

---

### Task 8: `DashboardController` — surface the rejection reason and re-upload link

**Files:**
- Modify: `src/main/java/org/confcms/cms/web/controller/DashboardController.java`
- Modify: `src/main/resources/templates/dashboard.html`
- Modify: `src/test/java/org/confcms/cms/web/controller/DashboardControllerTest.java`

- [ ] **Step 1: Update the failing/existing tests for the new constructor parameter and new model attribute**

Modify `src/test/java/org/confcms/cms/web/controller/DashboardControllerTest.java` — replace the full file:

```java
package org.confcms.cms.web.controller;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.domain.Registration;
import org.confcms.cms.registration.repository.RegistrationRepository;
import org.confcms.cms.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RegistrationRepository registrationRepository;

    private DashboardController controller;

    @BeforeEach
    void setUp() {
        controller = new DashboardController(userRepository, registrationRepository);
        // Not every test cares about registrations; stub leniently so tests that
        // don't touch it aren't penalized by Mockito's strict-stubs unused-stub check.
        lenient().when(registrationRepository.findByUserId(any())).thenReturn(List.of());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, Collections.emptyList()));
    }

    @Test
    void adminIsRedirectedToAdminDashboard() {
        User admin = new User();
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticateAs(admin);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));

        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        String view = controller.dashboard(request, model);

        assertThat(view).isEqualTo("redirect:/admin/dashboard");
    }

    @Test
    void reviewerSeesTheMinimalDashboardWithNoPendingPrompt() {
        User reviewer = new User();
        reviewer.setId(2L);
        reviewer.setEmail("reviewer@example.com");
        reviewer.setRole(Role.REVIEWER);
        authenticateAs(reviewer);
        when(userRepository.findByEmail("reviewer@example.com")).thenReturn(Optional.of(reviewer));

        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        String view = controller.dashboard(request, model);

        assertThat(view).isEqualTo("dashboard");
        assertThat(model.getAttribute("user")).isEqualTo(reviewer);
        assertThat(model.getAttribute("passwordPromptPending")).isEqualTo(false);
        assertThat(model.getAttribute("rejectedRegistration")).isNull();
    }

    @Test
    void authorSeesPasswordPromptWhenSessionFlagIsSet() {
        User author = new User();
        author.setId(3L);
        author.setEmail("author@example.com");
        author.setRole(Role.AUTHOR);
        authenticateAs(author);
        when(userRepository.findByEmail("author@example.com")).thenReturn(Optional.of(author));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("passwordPromptPending", true);
        Model model = new ExtendedModelMap();

        String view = controller.dashboard(request, model);

        assertThat(view).isEqualTo("dashboard");
        assertThat(model.getAttribute("passwordPromptPending")).isEqualTo(true);
    }

    @Test
    void authorSeesRejectedRegistrationWhenOneExists() {
        User author = new User();
        author.setId(4L);
        author.setEmail("author2@example.com");
        author.setRole(Role.AUTHOR);
        authenticateAs(author);
        when(userRepository.findByEmail("author2@example.com")).thenReturn(Optional.of(author));

        Registration rejected = new Registration();
        rejected.setPaymentStatus(PaymentStatus.FAILED);
        rejected.setRejectionReason("Illegible scan");
        when(registrationRepository.findByUserId(4L)).thenReturn(List.of(rejected));

        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        controller.dashboard(request, model);

        assertThat(model.getAttribute("rejectedRegistration")).isEqualTo(rejected);
    }

    @Test
    void authorDoesNotSeeAFailedRegistrationWithNoRejectionReasonAsRejected() {
        User author = new User();
        author.setId(5L);
        author.setEmail("author3@example.com");
        author.setRole(Role.AUTHOR);
        authenticateAs(author);
        when(userRepository.findByEmail("author3@example.com")).thenReturn(Optional.of(author));

        Registration failedNoReason = new Registration();
        failedNoReason.setPaymentStatus(PaymentStatus.FAILED);
        failedNoReason.setRejectionReason(null);
        when(registrationRepository.findByUserId(5L)).thenReturn(List.of(failedNoReason));

        MockHttpServletRequest request = new MockHttpServletRequest();
        Model model = new ExtendedModelMap();

        controller.dashboard(request, model);

        assertThat(model.getAttribute("rejectedRegistration")).isNull();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.DashboardControllerTest"`
Expected: FAIL — compile error, `DashboardController` constructor doesn't accept a second argument yet.

- [ ] **Step 3: Add the `RegistrationRepository` dependency and rejected-registration lookup**

Modify `src/main/java/org/confcms/cms/web/controller/DashboardController.java` — replace the full file:

```java
package org.confcms.cms.web.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.repository.RegistrationRepository;
import org.confcms.cms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@RequiredArgsConstructor
public class DashboardController {

    private final UserRepository userRepository;
    private final RegistrationRepository registrationRepository;

    @GetMapping("/dashboard")
    public String dashboard(HttpServletRequest request, Model model) {
        User user = currentUser();
        if (user.getRole() == Role.ADMIN) {
            return "redirect:/admin/dashboard";
        }
        model.addAttribute("user", user);
        var session = request.getSession(false);
        model.addAttribute("passwordPromptPending", session != null && session.getAttribute("passwordPromptPending") != null);

        registrationRepository.findByUserId(user.getId()).stream()
                .filter(r -> r.getPaymentStatus() == PaymentStatus.FAILED && r.getRejectionReason() != null)
                .findFirst()
                .ifPresent(r -> model.addAttribute("rejectedRegistration", r));

        return "dashboard";
    }

    private User currentUser() {
        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByEmail(email).orElseThrow(() -> new IllegalStateException("User not found"));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "org.confcms.cms.web.controller.DashboardControllerTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Add the rejection banner to `dashboard.html`**

Modify `src/main/resources/templates/dashboard.html` — insert immediately before the existing `<div th:if="${passwordPromptPending}" ...>` block:

```html
        <div th:if="${rejectedRegistration}" class="alert alert-danger">
            Your bank slip was rejected: <span th:text="${rejectedRegistration.rejectionReason}"></span>.
            <a th:href="@{/registration/{id}/reupload-slip(id=${rejectedRegistration.id})}">Upload a corrected slip</a>
        </div>

```

- [ ] **Step 6: Compile the full project**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/confcms/cms/web/controller/DashboardController.java src/main/resources/templates/dashboard.html src/test/java/org/confcms/cms/web/controller/DashboardControllerTest.java
git commit -m "feat: surface rejected bank-slip registrations and re-upload link on dashboard"
```

---

### Task 9: Final verification

**Files:** none (verification only)

- [ ] **Step 1: Run the full test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, zero failures.

- [ ] **Step 2: Boot the dev profile and verify the full bank-slip flow manually**

Run: `./gradlew bootRun` with the dev profile active. In a browser:
1. Log in as the dev admin.
2. If no LOCAL_BANK-provider conference exists in the dev seed data yet, create one via `/admin/conference/new` (select "Local Bank" as the payment provider, fill in bank details, mark it active).
3. Log out, log in (or register) as a non-admin dev user, visit `/register`.
4. Confirm the bank details render along with the new file-upload input.
5. Submit with no file — confirm the red error message appears and the form redisplays.
6. Submit with a valid PDF or JPEG file — confirm redirect to `/dashboard?registered=true`.
7. Log back in as the admin, visit `/admin/registrations` (via the new nav link on `/admin/dashboard`) — confirm the new registration appears in the "Awaiting Verification" list.
8. Click "View slip" — confirm the uploaded file opens/downloads correctly with the right content type.
9. Click "Reject" with a reason typed in — confirm the registration disappears from the pending list.
10. Log back in as the registrant — confirm the dashboard shows the rejection reason and a re-upload link.
11. Click the re-upload link, submit a new valid file — confirm redirect to `/dashboard?slipResubmitted=true` and that the registration reappears in the admin's pending list.
12. As admin, click "Approve" this time — confirm it disappears from the pending list (now `PAID`).

Stop the app once verified.

- [ ] **Step 3: Verify encryption end-to-end once more, now that the full schema exists**

While the app is running (or in a fresh boot), use the H2 console to inspect `conference_payment_configs` for the conference created in Step 2 — if it used LOCAL_BANK, no Stripe/PayPal secret was set, so also create a second throwaway conference with STRIPE selected and a test secret key, confirm via H2 console that `stripe_secret_key` is ciphertext, not plaintext. This second conference can be deleted afterward or simply left as harmless test data — note either choice in your final report.

- [ ] **Step 4: Report completion**

Summarize: all 9 tasks complete, full test suite green, manual verification results from Steps 2-3 above (including confirmation of the LOCAL_BANK dev-seed setup you had to do, if any, and whether the throwaway STRIPE conference was cleaned up or left in place).
