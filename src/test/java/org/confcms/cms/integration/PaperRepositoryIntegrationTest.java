package org.confcms.cms.integration;

import org.confcms.cms.auth.AuthService;
import org.confcms.cms.conference.Conference;
import org.confcms.cms.conference.ConferenceRepository;
import org.confcms.cms.core.security.Role;
import org.confcms.cms.paper.Paper;
import org.confcms.cms.paper.PaperRepository;
import org.confcms.cms.paper.PaperStatus;
import org.confcms.cms.paper.PaperVersion;
import org.confcms.cms.user.User;
import org.junit.jupiter.api.Test;

import jakarta.annotation.Resource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Exercises PaperRepository.findAllWithSubmitterAndVersions (the JOIN FETCH ... DISTINCT
// query added to fix the admin dashboard/papers-list N+1 -- see PaperRepository) against a
// real DB. A Mockito unit test can assert the method was called; only a real query execution
// can prove the JOIN FETCH + DISTINCT actually returns one row per paper, not one per version,
// and that submitter/versions come back populated without further lazy-loading.
class PaperRepositoryIntegrationTest extends IntegrationTest {

    @Resource
    private PaperRepository paperRepository;
    @Resource
    private ConferenceRepository conferenceRepository;
    @Resource
    private AuthService authService;

    @Test
    void findAllWithSubmitterAndVersionsReturnsOneRowPerPaperDespiteMultipleVersions() {
        Conference conference = new Conference();
        conference.setTitle("Integration Test Conference");
        conference.setVenue("Test Venue");
        conference.setStartDate(LocalDate.now());
        conference.setEndDate(LocalDate.now().plusDays(1));
        conference.setActive(true);
        conference = conferenceRepository.save(conference);

        User submitter = authService.registerUser("integration-paper-author@example.com", "Passw0rd!", "Paper Author", Role.AUTHOR);

        Paper paper = new Paper();
        paper.setConference(conference);
        paper.setTitle("A Paper With Two Versions");
        paper.setAbstractText("Abstract text");
        paper.setSubmitter(submitter);
        paper.setStatus(PaperStatus.SUBMITTED);
        paper.setTrack("Track A");

        PaperVersion v1 = new PaperVersion();
        v1.setPaper(paper);
        v1.setVersionNumber(1);
        v1.setFilePath("/tmp/v1.pdf");
        v1.setOriginalFilename("v1.pdf");
        paper.getVersions().add(v1);

        PaperVersion v2 = new PaperVersion();
        v2.setPaper(paper);
        v2.setVersionNumber(2);
        v2.setFilePath("/tmp/v2.pdf");
        v2.setOriginalFilename("v2.pdf");
        paper.getVersions().add(v2);

        paperRepository.save(paper);

        List<Paper> results = paperRepository.findAllWithSubmitterAndVersions();

        List<Paper> matching = results.stream().filter(p -> p.getTitle().equals("A Paper With Two Versions")).toList();
        assertThat(matching).hasSize(1);
        Paper loaded = matching.get(0);
        assertThat(loaded.getSubmitter().getFullName()).isEqualTo("Paper Author");
        assertThat(loaded.getVersions()).hasSize(2);
    }
}
