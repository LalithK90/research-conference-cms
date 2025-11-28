package com.icosiam.cms.service;

import com.icosiam.cms.domain.Conference;
import com.icosiam.cms.submission.domain.Paper;
import com.icosiam.cms.submission.domain.PaperStatus;
import com.icosiam.cms.submission.repository.PaperRepository;
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
import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProceedingsService {

    private final PaperRepository paperRepository;

    public void generateProceedings(Conference conference, String outputPath) throws IOException {
        List<Paper> acceptedPapers = paperRepository.findByStatus(PaperStatus.ACCEPTED);
        acceptedPapers.sort(Comparator.comparing(Paper::getTitle));

        PDFMergerUtility merger = new PDFMergerUtility();
        merger.setDestinationFileName(outputPath);

        // 1. Generate Cover Page (placeholder)
        File coverFile = generateCoverPage(conference);
        merger.addSource(coverFile);

        // 2. Generate TOC (placeholder)
        File tocFile = generateTableOfContents(acceptedPapers);
        merger.addSource(tocFile);

        // 3. Add Papers
        for (Paper paper : acceptedPapers) {
            if (!paper.getVersions().isEmpty()) {
                var latest = paper.getVersions().get(paper.getVersions().size() - 1);
                File pdfFile = new File(latest.getFilePath());
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
        List<Paper> acceptedPapers = paperRepository.findByStatus(PaperStatus.ACCEPTED);

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
