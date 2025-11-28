package com.icosiam.cms.submission.service;

import com.icosiam.cms.domain.User;
import com.icosiam.cms.core.service.FileStorageService;
import com.icosiam.cms.service.EmailService;
import com.icosiam.cms.submission.domain.*;
import com.icosiam.cms.submission.repository.PaperRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SubmissionService {

    private final PaperRepository paperRepository;
    private final FileStorageService fileStorageService;
    private final EmailService emailService;

    @Transactional
    public Paper submitPaper(User submitter, String title, String abstractText, String track, MultipartFile file, List<PaperAuthor> authors) {
        Paper paper = new Paper();
        paper.setSubmitter(submitter);
        paper.setTitle(title);
        paper.setAbstractText(abstractText);
        paper.setTrack(track);
        paper.setStatus(PaperStatus.SUBMITTED);

        // Validate and save file
        validatePdf(file);
        String filePath = fileStorageService.store(file);

        PaperVersion version = new PaperVersion();
        version.setPaper(paper);
        version.setVersionNumber(1);
        version.setFilePath(filePath);
        version.setOriginalFilename(file.getOriginalFilename());
        paper.getVersions().add(version);

        // Set authors
        for (PaperAuthor author : authors) {
            author.setPaper(paper);
            paper.getAuthors().add(author);
        }

        Paper saved = paperRepository.save(paper);

        // Send submission confirmation (templated)
        try {
            java.util.Map<String, Object> model = new java.util.HashMap<>();
            model.put("submitterName", submitter.getFullName());
            model.put("paperTitle", paper.getTitle());
            model.put("paperId", saved.getId());
            model.put("track", paper.getTrack());
            emailService.sendTemplateEmail(submitter.getEmail(), "Submission Received", "email/submission_confirmation.txt", model);
        } catch (Exception ignored) {}

        return saved;
    }

    private void validatePdf(MultipartFile file) {
        try {
            // Quick sanity check: PDF files start with "%PDF-"
            try (var in = file.getInputStream()) {
                byte[] header = new byte[5];
                int read = in.read(header);
                if (read < 5) {
                    throw new IllegalArgumentException("Uploaded file is too small to be a PDF");
                }
                String sig = new String(header);
                if (!sig.equals("%PDF-")) {
                    throw new IllegalArgumentException("Uploaded file is not a valid PDF");
                }
            }
        } catch (IllegalArgumentException iae) {
            throw iae;
        } catch (Exception e) {
            throw new RuntimeException("File validation failed: not a valid PDF", e);
        }
    }

    public List<Paper> getPapersBySubmitter(User submitter) {
        return paperRepository.findBySubmitterId(submitter.getId());
    }
    
    public List<Paper> getAllPapers() {
        return paperRepository.findAll();
    }

    @Transactional
    public Paper uploadNewVersion(User requester, Long paperId, MultipartFile file) {
        Paper paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new IllegalArgumentException("Paper not found"));

        // Only submitter or ADMIN can upload new versions
        boolean isOwner = paper.getSubmitter().getId().equals(requester.getId());
        boolean isAdmin = requester.getRole() == com.icosiam.cms.domain.Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new SecurityException("Not authorized to upload new version");
        }

        if (paper.getStatus() == PaperStatus.WITHDRAWN) {
            throw new IllegalStateException("Cannot upload versions for withdrawn paper");
        }

        validatePdf(file);
        String filePath = fileStorageService.store(file);

        int newVersionNumber = paper.getVersions().size() + 1;
        PaperVersion version = new PaperVersion();
        version.setPaper(paper);
        version.setVersionNumber(newVersionNumber);
        version.setFilePath(filePath);
        version.setOriginalFilename(file.getOriginalFilename());
        paper.getVersions().add(version);
        Paper saved = paperRepository.save(paper);

        // Notify submitter
        try {
            emailService.sendSimpleEmail(paper.getSubmitter().getEmail(), "Paper updated: new version uploaded",
                    "A new version (v" + newVersionNumber + ") was uploaded for your paper: " + paper.getTitle());
        } catch (Exception ignored) {
        }

        return saved;
    }

    @Transactional
    public Paper withdrawPaper(User requester, Long paperId) {
        Paper paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new IllegalArgumentException("Paper not found"));

        // Only submitter or ADMIN can withdraw
        boolean isOwner = paper.getSubmitter().getId().equals(requester.getId());
        boolean isAdmin = requester.getRole() == com.icosiam.cms.domain.Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new SecurityException("Not authorized to withdraw this paper");
        }

        paper.setStatus(PaperStatus.WITHDRAWN);
        Paper saved = paperRepository.save(paper);

        try {
            emailService.sendSimpleEmail(paper.getSubmitter().getEmail(), "Paper withdrawn",
                    "Your paper '" + paper.getTitle() + "' has been withdrawn.");
        } catch (Exception ignored) {
        }


        return saved;
        }

    }


