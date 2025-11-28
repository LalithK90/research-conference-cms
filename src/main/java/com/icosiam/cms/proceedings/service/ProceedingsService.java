package com.icosiam.cms.proceedings.service;

import com.icosiam.cms.submission.domain.Paper;
import com.icosiam.cms.submission.domain.PaperStatus;
import com.icosiam.cms.submission.domain.PaperVersion;
import com.icosiam.cms.submission.repository.PaperRepository;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

@Service("proceedingsServiceLegacy")
@Profile("!dev")
@RequiredArgsConstructor
public class ProceedingsService {

    private final PaperRepository paperRepository;

    public void generateProceedings(String outputPath) throws IOException {
        List<Paper> acceptedPapers = paperRepository.findByStatus(PaperStatus.ACCEPTED);
        acceptedPapers.sort(Comparator.comparing(Paper::getTitle)); // Sort by title

        PDFMergerUtility merger = new PDFMergerUtility();
        merger.setDestinationFileName(outputPath);

        // 1. Generate Cover Page (minimal placeholder to avoid font dependencies)
        PDDocument coverDoc = new PDDocument();
        PDPage coverPage = new PDPage();
        coverDoc.addPage(coverPage);
        File coverFile = File.createTempFile("cover", ".pdf");
        coverDoc.save(coverFile);
        coverDoc.close();
        merger.addSource(coverFile);

        // 2. Generate TOC (placeholder)
        PDDocument tocDoc = new PDDocument();
        PDPage tocPage = new PDPage();
        tocDoc.addPage(tocPage);
        File tocFile = File.createTempFile("toc", ".pdf");
        tocDoc.save(tocFile);
        tocDoc.close();
        merger.addSource(tocFile);

        // 3. Add Papers
        for (Paper paper : acceptedPapers) {
            if (!paper.getVersions().isEmpty()) {
                // Get latest version
                PaperVersion latest = paper.getVersions().get(paper.getVersions().size() - 1);
                merger.addSource(new File(latest.getFilePath()));
            }
        }

        merger.mergeDocuments(null);
        
        // Cleanup temp files
        coverFile.delete();
        tocFile.delete();
    }
}
