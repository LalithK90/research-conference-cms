package org.confcms.cms.service;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.submission.domain.Paper;
import org.confcms.cms.submission.domain.PaperStatus;
import org.confcms.cms.submission.domain.PaperVersion;
import org.confcms.cms.submission.repository.PaperRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProceedingsServiceTest {

    @Mock
    private PaperRepository paperRepository;

    @Test
    void generateProceedingsOnlyQueriesCameraReadySubmittedPapers() throws Exception {
        ProceedingsService service = new ProceedingsService(paperRepository);
        when(paperRepository.findByStatus(PaperStatus.CAMERA_READY_SUBMITTED)).thenReturn(List.of());

        Conference conference = new Conference();
        conference.setTitle("Test Conf");
        conference.setStartDate(LocalDate.of(2026, 1, 1));

        File outputFile = File.createTempFile("proceedings-test", ".pdf");
        outputFile.deleteOnExit();

        service.generateProceedings(conference, outputFile.getAbsolutePath());

        verify(paperRepository).findByStatus(PaperStatus.CAMERA_READY_SUBMITTED);
    }

    @Test
    void exportBibTeXOnlyQueriesCameraReadySubmittedPapers() throws Exception {
        ProceedingsService service = new ProceedingsService(paperRepository);
        when(paperRepository.findByStatus(PaperStatus.CAMERA_READY_SUBMITTED)).thenReturn(List.of());

        Conference conference = new Conference();
        conference.setTitle("Test Conf");
        conference.setStartDate(LocalDate.of(2026, 1, 1));

        File outputFile = File.createTempFile("bibtex-test", ".bib");
        outputFile.deleteOnExit();

        service.exportBibTeX(conference, outputFile.getAbsolutePath());

        verify(paperRepository).findByStatus(PaperStatus.CAMERA_READY_SUBMITTED);
    }

    @Test
    void generateProceedingsSelectsTheCameraReadyFlaggedVersionNotJustTheLastOne() throws Exception {
        ProceedingsService service = new ProceedingsService(paperRepository);

        Paper paper = new Paper();
        paper.setId(1L);
        paper.setTitle("A Paper");
        paper.setStatus(PaperStatus.CAMERA_READY_SUBMITTED);

        // The camera-ready-flagged version has 1 page.
        File cameraReadyPdf = File.createTempFile("camera-ready", ".pdf");
        writeMinimalPdf(cameraReadyPdf, 1);
        cameraReadyPdf.deleteOnExit();

        PaperVersion cameraReadyVersion = new PaperVersion();
        cameraReadyVersion.setVersionNumber(2);
        cameraReadyVersion.setFilePath(cameraReadyPdf.getAbsolutePath());
        cameraReadyVersion.setCameraReady(true);
        paper.getVersions().add(cameraReadyVersion);

        // A later, non-camera-ready version has a different page count (3) so that if the
        // implementation regresses to "take the last version", the merged output's page count
        // would differ and this test would fail instead of silently passing either way.
        File laterNonCameraReadyPdf = File.createTempFile("later-non-camera-ready", ".pdf");
        writeMinimalPdf(laterNonCameraReadyPdf, 3);
        laterNonCameraReadyPdf.deleteOnExit();
        PaperVersion laterVersion = new PaperVersion();
        laterVersion.setVersionNumber(3);
        laterVersion.setFilePath(laterNonCameraReadyPdf.getAbsolutePath());
        laterVersion.setCameraReady(false);
        paper.getVersions().add(laterVersion);

        when(paperRepository.findByStatus(PaperStatus.CAMERA_READY_SUBMITTED)).thenReturn(List.of(paper));

        Conference conference = new Conference();
        conference.setTitle("Test Conf");
        conference.setStartDate(LocalDate.of(2026, 1, 1));

        File outputFile = File.createTempFile("proceedings-selection-test", ".pdf");
        outputFile.deleteOnExit();

        service.generateProceedings(conference, outputFile.getAbsolutePath());

        // Cover page (1) + TOC page (1) + camera-ready version's page count (1) = 3.
        // If the implementation regressed to "last version", the later 3-page version would be
        // merged instead, giving 5 pages -- a concrete, falsifiable difference.
        try (var merged = org.apache.pdfbox.Loader.loadPDF(outputFile)) {
            assertThat(merged.getNumberOfPages()).isEqualTo(3);
        }
    }

    private void writeMinimalPdf(File file, int pageCount) throws Exception {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            }
            doc.save(file);
        }
    }
}
