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

        // Ticket-type validation happens before the duplicate-registration lookup in
        // RegistrationService.register, so no repository stub is needed here.
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
        User u = user(1L);
        Conference c = conferenceWithProvider(10L, org.confcms.cms.domain.PaymentProvider.LOCAL_BANK);
        Registration reg = new Registration();
        reg.setUser(u);
        reg.setConference(c);
        reg.setPaymentStatus(PaymentStatus.FAILED);
        reg.setRejectionReason("Illegible scan");
        MultipartFile slip = new MockMultipartFile("bankSlip", "corrected.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
        when(registrationRepository.findByUserIdAndConferenceIdAndPaymentStatusNot(1L, 10L, PaymentStatus.FAILED))
                .thenReturn(Optional.empty());
        when(fileStorageService.store(slip)).thenReturn("/uploads/uuid_corrected.jpg");
        when(registrationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.reuploadSlip(reg, slip);

        assertThat(reg.getPaymentStatus()).isEqualTo(PaymentStatus.AWAITING_VERIFICATION);
        assertThat(reg.getBankSlipPath()).isEqualTo("/uploads/uuid_corrected.jpg");
        assertThat(reg.getBankSlipOriginalFilename()).isEqualTo("corrected.jpg");
        assertThat(reg.getRejectionReason()).isNull();
    }

    @Test
    void reuploadSlipRejectsWhenAnotherActiveRegistrationExistsForTheSameConference() {
        // Guards against: reject -> register again (new AWAITING_VERIFICATION row) -> then
        // re-upload the OLD rejected row too, which would leave two simultaneously-active
        // registrations for the same user+conference.
        User u = user(1L);
        Conference c = conferenceWithProvider(10L, org.confcms.cms.domain.PaymentProvider.LOCAL_BANK);
        Registration staleRejected = new Registration();
        staleRejected.setUser(u);
        staleRejected.setConference(c);
        staleRejected.setPaymentStatus(PaymentStatus.FAILED);
        staleRejected.setRejectionReason("Illegible scan");
        MultipartFile slip = new MockMultipartFile("bankSlip", "corrected.jpg", "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00});
        when(registrationRepository.findByUserIdAndConferenceIdAndPaymentStatusNot(1L, 10L, PaymentStatus.FAILED))
                .thenReturn(Optional.of(new Registration()));

        assertThatThrownBy(() -> service.reuploadSlip(staleRejected, slip))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already have an active registration");

        verify(fileStorageService, never()).store(any());
        verify(registrationRepository, never()).save(any());
    }
}
