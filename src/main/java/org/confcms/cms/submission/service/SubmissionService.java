package org.confcms.cms.submission.service;

import org.confcms.cms.user.User;
import org.confcms.cms.user.UserRepository;
import org.confcms.cms.service.ConferenceService;
import org.confcms.cms.service.FileStorageService;
import org.confcms.cms.service.EmailService;
import org.confcms.cms.personinvitation.PersonInvitationService;
import org.confcms.cms.submission.domain.*;
import org.confcms.cms.submission.repository.PaperRepository;
import org.confcms.cms.submission.repository.PaperVersionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

    private final PaperRepository paperRepository;
    private final FileStorageService fileStorageService;
    private final EmailService emailService;
    private final ConferenceService conferenceService;
    private final PersonInvitationService personInvitationService;
    private final UserRepository userRepository;
    private final PaperVersionRepository paperVersionRepository;

    @Transactional
    public Paper submitPaper(User submitter, String title, String abstractText, String track, MultipartFile file, List<PaperAuthor> authors) {
        Paper paper = new Paper();
        paper.setConference(conferenceService.getActiveConference());
        paper.setSubmitter(submitter);
        paper.setTitle(title);
        paper.setAbstractText(abstractText);
        paper.setTrack(track);
        paper.setStatus(PaperStatus.SUBMITTED);

        // Validate and save file
        validatePdf(file);
        String contentHash = computeContentHash(file);
        String filePath = fileStorageService.store(file);

        PaperVersion version = new PaperVersion();
        version.setPaper(paper);
        version.setVersionNumber(1);
        version.setFilePath(filePath);
        version.setOriginalFilename(file.getOriginalFilename());
        applyDuplicateCheck(version, contentHash);
        paper.getVersions().add(version);

        // Set authors
        for (PaperAuthor author : authors) {
            author.setPaper(paper);
            paper.getAuthors().add(author);
        }

        // Invite any co-author who isn't already a registered User
        for (PaperAuthor author : authors) {
            if (userRepository.findByEmail(author.getEmail()).isEmpty()) {
                personInvitationService.inviteCoAuthor(paper, author);
            }
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

    private String computeContentHash(MultipartFile file) {
        try (var in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            log.warn("Failed to compute content hash for uploaded file; duplicate detection skipped for this upload", e);
            return null;
        }
    }

    private void applyDuplicateCheck(PaperVersion version, String contentHash) {
        version.setContentHash(contentHash);
        if (contentHash == null) {
            return;
        }
        List<PaperVersion> matches = paperVersionRepository.findByContentHash(contentHash).stream()
                .filter(match -> !match.getPaper().getId().equals(version.getPaper().getId()))
                .toList();
        if (!matches.isEmpty()) {
            version.setPossibleDuplicate(true);
            version.setDuplicateOfPaperVersionId(matches.get(0).getId());
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
        boolean isAdmin = requester.getRole() == org.confcms.cms.core.security.Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new SecurityException("Not authorized to upload new version");
        }

        if (paper.getStatus() == PaperStatus.WITHDRAWN) {
            throw new IllegalStateException("Cannot upload versions for withdrawn paper");
        }

        // Once a paper is ACCEPTED (or further along), new versions must go through
        // uploadCameraReady so its own checks (ACCEPTED-status requirement, copyright transfer
        // agreement) can't be bypassed via this generic endpoint. MINOR/MAJOR_REVISION stay
        // allowed here -- they fall through to enforceRevisionDeadline below, which is this
        // same guard for the revision flow.
        if (paper.getStatus() == PaperStatus.ACCEPTED
                || paper.getStatus() == PaperStatus.REJECTED
                || paper.getStatus() == PaperStatus.DESK_REJECTED
                || paper.getStatus() == PaperStatus.CAMERA_READY_SUBMITTED) {
            throw new IllegalStateException("This paper is not open for a plain version upload at its current stage");
        }

        enforceRevisionDeadline(paper);

        validatePdf(file);
        String contentHash = computeContentHash(file);
        String filePath = fileStorageService.store(file);

        int newVersionNumber = paper.getVersions().size() + 1;
        PaperVersion version = new PaperVersion();
        version.setPaper(paper);
        version.setVersionNumber(newVersionNumber);
        version.setFilePath(filePath);
        version.setOriginalFilename(file.getOriginalFilename());
        applyDuplicateCheck(version, contentHash);
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

    // If the paper is currently awaiting a revision (MINOR/MAJOR_REVISION) and its due date has
    // passed, auto-reject it and refuse the upload. Shared by uploadNewVersion and uploadRevision
    // so the deadline can't be bypassed by calling the "wrong" endpoint (the plain version-upload
    // endpoint predates the revision workflow and would otherwise skip this check entirely).
    private void enforceRevisionDeadline(Paper paper) {
        boolean awaitingRevision = paper.getStatus() == PaperStatus.MINOR_REVISION || paper.getStatus() == PaperStatus.MAJOR_REVISION;
        if (awaitingRevision && paper.getRevisionDueDate() != null && paper.getRevisionDueDate().isBefore(LocalDate.now())) {
            paper.setStatus(PaperStatus.REJECTED);
            paper.setRevisionDueDate(null);
            paper.setRevisionRequestedAtVersionCount(null);
            paperRepository.save(paper);
            throw new IllegalStateException("The revision deadline has passed; this paper has been rejected");
        }
    }

    @Transactional
    public Paper withdrawPaper(User requester, Long paperId) {
        Paper paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new IllegalArgumentException("Paper not found"));

        // Only submitter or ADMIN can withdraw
        boolean isOwner = paper.getSubmitter().getId().equals(requester.getId());
        boolean isAdmin = requester.getRole() == org.confcms.cms.core.security.Role.ADMIN;
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

    @Transactional
    public Paper uploadRevision(User requester, Long paperId, MultipartFile file) {
        Paper paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new IllegalArgumentException("Paper not found"));

        boolean isOwner = paper.getSubmitter().getId().equals(requester.getId());
        boolean isAdmin = requester.getRole() == org.confcms.cms.core.security.Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new SecurityException("Not authorized to upload a revision for this paper");
        }

        if (paper.getStatus() != PaperStatus.MINOR_REVISION && paper.getStatus() != PaperStatus.MAJOR_REVISION) {
            throw new IllegalStateException("This paper is not currently awaiting a revision");
        }

        enforceRevisionDeadline(paper);

        validatePdf(file);
        String contentHash = computeContentHash(file);
        String filePath = fileStorageService.store(file);

        int newVersionNumber = paper.getVersions().size() + 1;
        PaperVersion version = new PaperVersion();
        version.setPaper(paper);
        version.setVersionNumber(newVersionNumber);
        version.setFilePath(filePath);
        version.setOriginalFilename(file.getOriginalFilename());
        applyDuplicateCheck(version, contentHash);
        paper.getVersions().add(version);

        return paperRepository.save(paper);
    }

    @Transactional
    public Paper uploadCameraReady(User requester, Long paperId, MultipartFile file, boolean copyrightAgreed) {
        Paper paper = paperRepository.findById(paperId)
                .orElseThrow(() -> new IllegalArgumentException("Paper not found"));

        boolean isOwner = paper.getSubmitter().getId().equals(requester.getId());
        boolean isAdmin = requester.getRole() == org.confcms.cms.core.security.Role.ADMIN;
        if (!isOwner && !isAdmin) {
            throw new SecurityException("Not authorized to upload a camera-ready version for this paper");
        }

        if (paper.getStatus() != PaperStatus.ACCEPTED) {
            throw new IllegalStateException("This paper is not currently awaiting a camera-ready submission");
        }

        if (!copyrightAgreed) {
            throw new IllegalStateException("Copyright transfer must be agreed to before submitting the camera-ready version");
        }

        validatePdf(file);
        String contentHash = computeContentHash(file);
        String filePath = fileStorageService.store(file);

        int newVersionNumber = paper.getVersions().size() + 1;
        PaperVersion version = new PaperVersion();
        version.setPaper(paper);
        version.setVersionNumber(newVersionNumber);
        version.setFilePath(filePath);
        version.setOriginalFilename(file.getOriginalFilename());
        version.setCameraReady(true);
        version.setCopyrightTransferAgreedAt(java.time.Instant.now());
        applyDuplicateCheck(version, contentHash);
        paper.getVersions().add(version);

        paper.setStatus(PaperStatus.CAMERA_READY_SUBMITTED);
        Paper saved = paperRepository.save(paper);

        try {
            emailService.sendSimpleEmail(paper.getSubmitter().getEmail(), "Camera-ready version received",
                    "Your camera-ready version (v" + newVersionNumber + ") was received for your paper: " + paper.getTitle());
        } catch (Exception ignored) {
        }

        return saved;
    }

    }


