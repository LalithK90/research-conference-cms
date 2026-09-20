package org.confcms.cms.web.controller;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.PersonInvitation;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.ConferenceRepository;
import org.confcms.cms.repository.PersonInvitationRepository;
import org.confcms.cms.repository.UserRepository;
import org.confcms.cms.service.CommitteeService;
import org.confcms.cms.service.PersonInvitationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonInvitationAdminControllerTest {

    @Mock
    private PersonInvitationService personInvitationService;
    @Mock
    private PersonInvitationRepository personInvitationRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CommitteeService committeeService;
    @Mock
    private ConferenceRepository conferenceRepository;

    private PersonInvitationAdminController controller;
    private Conference conference;

    @BeforeEach
    void setUp() {
        controller = new PersonInvitationAdminController(personInvitationService, personInvitationRepository, userRepository, committeeService, conferenceRepository);

        conference = new Conference();
        conference.setId(1L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(User user) {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, Collections.emptyList()));
    }

    @Test
    void listForConferenceDeniesReviewerWithNoCommitteeRoleOnConference() {
        User stranger = new User();
        stranger.setId(30L);
        stranger.setEmail("stranger@example.com");
        stranger.setRole(Role.REVIEWER);
        authenticateAs(stranger);

        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        when(committeeService.isChairOrCoChair(stranger, conference)).thenReturn(false);

        ResponseEntity<?> response = controller.listForConference(1L);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void listForConferenceAllowsChairAndOmitsToken() {
        User chair = new User();
        chair.setId(20L);
        chair.setEmail("chair@example.com");
        chair.setRole(Role.REVIEWER);
        authenticateAs(chair);

        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        when(committeeService.isChairOrCoChair(chair, conference)).thenReturn(true);

        PersonInvitation invitation = new PersonInvitation();
        invitation.setId(100L);
        invitation.setName("Jane Doe");
        invitation.setEmail("jane@example.com");
        invitation.setToken("secret-token-value");
        when(personInvitationRepository.findByConferenceId(1L)).thenReturn(List.of(invitation));

        ResponseEntity<?> response = controller.listForConference(1L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody().toString();
        assertThat(body).doesNotContain("secret-token-value");
    }

    @Test
    void listForConferenceAllowsAdminRegardlessOfCommitteeRole() {
        User admin = new User();
        admin.setId(40L);
        admin.setEmail("admin@example.com");
        admin.setRole(Role.ADMIN);
        authenticateAs(admin);

        when(conferenceRepository.findById(1L)).thenReturn(Optional.of(conference));
        when(personInvitationRepository.findByConferenceId(1L)).thenReturn(Collections.emptyList());

        ResponseEntity<?> response = controller.listForConference(1L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
