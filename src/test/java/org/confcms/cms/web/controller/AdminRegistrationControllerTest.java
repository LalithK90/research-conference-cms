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
