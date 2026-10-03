package org.confcms.cms.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class FileStorageServiceTest {

    private Path tempDir;
    private FileStorageService service;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("file-storage-service-test");
        service = new FileStorageService(tempDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    @Test
    void storeIgnoresMaliciousExtensionAndUsesDetectedPdfExtension() {
        byte[] pdfBytes = "%PDF-1.4 fake pdf content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "evil.html", "application/pdf", pdfBytes);

        String storedPath = service.store(file);

        assertThat(storedPath).endsWith(".pdf");
        assertThat(storedPath).doesNotContain(".html");
    }

    @Test
    void storePreservesPdfExtensionForGenuinePdf() {
        byte[] pdfBytes = "%PDF-1.4 fake pdf content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", pdfBytes);

        String storedPath = service.store(file);

        assertThat(storedPath).endsWith(".pdf");
    }

    @Test
    void storeDetectsJpegAndUsesJpgExtensionRegardlessOfClaimedName() {
        byte[] jpegBytes = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02};
        MockMultipartFile file = new MockMultipartFile("file", "slip.exe", "image/jpeg", jpegBytes);

        String storedPath = service.store(file);

        assertThat(storedPath).endsWith(".jpg");
        assertThat(storedPath).doesNotContain(".exe");
    }

    @Test
    void storeDetectsPngAndUsesPngExtensionRegardlessOfClaimedName() {
        byte[] pngBytes = {(byte) 0x89, 'P', 'N', 'G', 0x00, 0x01};
        MockMultipartFile file = new MockMultipartFile("file", "slip.svg", "image/png", pngBytes);

        String storedPath = service.store(file);

        assertThat(storedPath).endsWith(".png");
        assertThat(storedPath).doesNotContain(".svg");
    }

    @Test
    void storeFallsBackToBinExtensionForUnrecognizedContent() {
        byte[] unknownBytes = {0x00, 0x01, 0x02, 0x03};
        MockMultipartFile file = new MockMultipartFile("file", "whatever.html", "application/octet-stream", unknownBytes);

        String storedPath = service.store(file);

        assertThat(storedPath).endsWith(".bin");
        assertThat(storedPath).doesNotContain(".html");
    }

    @Test
    void storedFileContentMatchesOriginalBytes() throws IOException {
        byte[] pdfBytes = "%PDF-1.4 real content here".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "paper.pdf", "application/pdf", pdfBytes);

        String storedPath = service.store(file);

        assertThat(Files.readAllBytes(Path.of(storedPath))).isEqualTo(pdfBytes);
    }
}
