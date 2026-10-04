package org.confcms.cms.decision;

import org.confcms.cms.conference.Conference;
import org.confcms.cms.paper.Paper;
import org.confcms.cms.paper.PaperStatus;
import org.confcms.cms.paper.PaperVersion;
import org.confcms.cms.paper.PaperRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProceedingsService {

    private final PaperRepository paperRepository;

    public void generateProceedings(Conference conference, String outputPath) throws IOException {
        List<Paper> acceptedPapers = new ArrayList<>(
                paperRepository.findByConferenceIdAndStatus(conference.getId(), PaperStatus.CAMERA_READY_SUBMITTED));
        acceptedPapers.sort(Comparator.comparing(Paper::getTitle));

        PDFMergerUtility merger = new PDFMergerUtility();
        merger.setDestinationFileName(outputPath);

        // 1. Generate Cover Page (placeholder)
        File coverFile = generateCoverPage(conference);
        merger.addSource(coverFile);

        // 2. Generate TOC (placeholder)
        File tocFile = generateTableOfContents(acceptedPapers);
        merger.addSource(tocFile);

        // 3. Add Papers (specifically the camera-ready-flagged version, not "last uploaded" --
        // a paper can have later non-camera-ready versions e.g. from before it reached this stage
        // in a different run, so "last in the list" is not a safe substitute for the explicit flag)
        for (Paper paper : acceptedPapers) {
            Optional<PaperVersion> cameraReadyVersion = paper.getVersions().stream()
                    .filter(PaperVersion::isCameraReady)
                    .findFirst();
            if (cameraReadyVersion.isPresent()) {
                File pdfFile = new File(cameraReadyVersion.get().getFilePath());
                if (pdfFile.exists()) {
                    merger.addSource(pdfFile);
                }
            }
        }

        merger.mergeDocuments(null);

        // Cleanup temp files
        coverFile.delete();
        tocFile.delete();

        log.info("Proceedings generated: {}", outputPath);
    }

    private File generateCoverPage(Conference conference) throws IOException {
        PDDocument document = new PDDocument();
        PDPage page = new PDPage();
        document.addPage(page);

        // Minimal placeholder page without font-specific calls to avoid dependency issues

        File tempFile = File.createTempFile("cover", ".pdf");
        document.save(tempFile);
        document.close();
        return tempFile;
    }

    private File generateTableOfContents(List<Paper> papers) throws IOException {
        PDDocument document = new PDDocument();
        PDPage page = new PDPage();
        document.addPage(page);

        // Minimal placeholder page without font-specific calls to avoid dependency issues

        File tempFile = File.createTempFile("toc", ".pdf");
        document.save(tempFile);
        document.close();
        return tempFile;
    }

    public void exportBibTeX(Conference conference, String outputPath) throws IOException {
        List<Paper> acceptedPapers =
                paperRepository.findByConferenceIdAndStatus(conference.getId(), PaperStatus.CAMERA_READY_SUBMITTED);

        try (FileWriter writer = new FileWriter(outputPath)) {
            for (Paper paper : acceptedPapers) {
                String ref = String.format("paper-%d", paper.getId());
                writer.write(String.format("@inproceedings{%s,\n", ref));
                writer.write(String.format("  title={%s},\n", paper.getTitle()));
                writer.write(String.format("  author={%s},\n", paper.getSubmitter().getFullName()));
                writer.write(String.format("  booktitle={%s},\n", conference.getTitle()));
                writer.write(String.format("  year={%d}\n", conference.getStartDate().getYear()));
                writer.write("}\n\n");
            }
        }
    }
}
