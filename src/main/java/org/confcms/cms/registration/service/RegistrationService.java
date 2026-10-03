package org.confcms.cms.registration.service;

import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.PaymentProvider;
import org.confcms.cms.user.User;
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
        requireNotResolved(registration);
        registration.setPaymentStatus(PaymentStatus.PAID);
        registration.setRejectionReason(null);
        registrationRepository.save(registration);
    }

    @Transactional
    public void reject(Long registrationId, String reason) {
        Registration registration = getRegistration(registrationId);
        requireNotResolved(registration);
        registration.setPaymentStatus(PaymentStatus.FAILED);
        registration.setRejectionReason(reason == null || reason.isBlank() ? "No reason provided" : reason);
        registrationRepository.save(registration);
    }

    // Guards against a stale/replayed admin request re-deciding a registration that was
    // already approved or rejected (e.g. two admins racing on the same AWAITING_VERIFICATION
    // row, or a double-submitted form).
    private void requireNotResolved(Registration registration) {
        PaymentStatus status = registration.getPaymentStatus();
        if (status == PaymentStatus.PAID || status == PaymentStatus.FAILED) {
            throw new IllegalStateException("This registration has already been resolved (" + status + ")");
        }
    }

    @Transactional(readOnly = true)
    public Registration getRegistration(Long id) {
        return registrationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Registration not found"));
    }

    @Transactional
    public void reuploadSlip(Registration registration, MultipartFile bankSlip) {
        registrationRepository.findByUserIdAndConferenceIdAndPaymentStatusNot(
                        registration.getUser().getId(), registration.getConference().getId(), PaymentStatus.FAILED)
                .ifPresent(existing -> {
                    throw new IllegalArgumentException(
                            "You already have an active registration for this conference -- re-upload isn't needed");
                });

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
