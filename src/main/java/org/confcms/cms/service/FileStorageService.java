package org.confcms.cms.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class FileStorageService {

    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G'};

    private final Path rootLocation;

    public FileStorageService(@Value("${app.storage.location:uploads}") String storageLocation) {
        this.rootLocation = Paths.get(storageLocation);
        init();
    }

    public void init() {
        try {
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize storage", e);
        }
    }

    // /uploads/** (WebConfig) serves stored files statically with content-type inferred from the
    // file extension, not sniffed. Trusting the client-supplied originalFilename's extension would
    // let a crafted filename (e.g. "x.html" with valid PDF magic bytes, which already passes every
    // caller's own magic-byte content validation) get served back as text/html from this app's own
    // origin -- a stored-XSS angle. Detecting the extension from the file's own bytes, ignoring
    // whatever extension the client claimed, closes this at the one place every upload passes
    // through, with no change to serving or to any caller.
    public String store(MultipartFile file) {
        try {
            if (file.isEmpty()) {
                throw new IllegalArgumentException("Cannot store empty file");
            }

            String extension = detectExtension(file);
            String filename = UUID.randomUUID() + "_" + stripExtension(file.getOriginalFilename()) + extension;
            Path destinationFile = rootLocation.resolve(filename).normalize().toAbsolutePath();

            if (!destinationFile.getParent().equals(rootLocation.toAbsolutePath())) {
                throw new SecurityException("Cannot store file outside designated directory");
            }

            try (var inputStream = file.getInputStream()) {
                Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            }

            return destinationFile.toString();
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }
    }

    private String detectExtension(MultipartFile file) throws IOException {
        byte[] header = new byte[8];
        try (var in = file.getInputStream()) {
            int read = in.read(header);
            if (read < 0) {
                return ".bin";
            }
        }
        if (startsWith(header, PDF_SIGNATURE)) {
            return ".pdf";
        }
        if (startsWith(header, JPEG_SIGNATURE)) {
            return ".jpg";
        }
        if (startsWith(header, PNG_SIGNATURE)) {
            return ".png";
        }
        return ".bin";
    }

    private static boolean startsWith(byte[] header, byte[] signature) {
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

    // Keeps the original filename's base (sans extension) in the stored name purely for
    // human-readability in directory listings -- the extension itself is never trusted.
    private static String stripExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "file";
        }
        int lastDot = originalFilename.lastIndexOf('.');
        return lastDot > 0 ? originalFilename.substring(0, lastDot) : originalFilename;
    }

    public Path load(String filename) {
        return rootLocation.resolve(filename);
    }
}
