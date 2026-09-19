package org.confcms.cms.service;

import org.confcms.cms.domain.CommitteeRole;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.ConferenceCommitteeRole;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.ConferenceCommitteeRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommitteeServiceTest {

    @Mock
    private ConferenceCommitteeRoleRepository repository;

    private CommitteeService service;
    private Conference conference;
    private User user;

    @BeforeEach
    void setUp() {
        service = new CommitteeService(repository);
        conference = new Conference();
        conference.setId(1L);
        user = new User();
        user.setId(10L);
    }

    @Test
    void isChairOrCoChairTrueForChair() {
        ConferenceCommitteeRole chairRole = new ConferenceCommitteeRole();
        chairRole.setRole(CommitteeRole.CHAIR);
        when(repository.findByConferenceIdAndUserIdAndRole(1L, 10L, CommitteeRole.CHAIR))
                .thenReturn(Optional.of(chairRole));

        assertThat(service.isChairOrCoChair(user, conference)).isTrue();
    }

    @Test
    void isChairOrCoChairFalseForReviewerOnly() {
        when(repository.findByConferenceIdAndUserIdAndRole(1L, 10L, CommitteeRole.CHAIR))
                .thenReturn(Optional.empty());
        when(repository.findByConferenceIdAndUserIdAndRole(1L, 10L, CommitteeRole.CO_CHAIR))
                .thenReturn(Optional.empty());

        assertThat(service.isChairOrCoChair(user, conference)).isFalse();
    }

    @Test
    void assignChairDemotesPriorChair() {
        ConferenceCommitteeRole priorChair = new ConferenceCommitteeRole();
        priorChair.setId(99L);
        priorChair.setRole(CommitteeRole.CHAIR);
        when(repository.findByConferenceIdAndRole(1L, CommitteeRole.CHAIR))
                .thenReturn(Optional.of(priorChair));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.assignChair(conference, user);

        verify(repository).delete(priorChair);
        verify(repository).save(argThat(role -> role.getRole() == CommitteeRole.CHAIR && role.getUser() == user));
    }

    @Test
    void addRoleRejectsDuplicate() {
        ConferenceCommitteeRole existing = new ConferenceCommitteeRole();
        when(repository.findByConferenceIdAndUserIdAndRole(1L, 10L, CommitteeRole.REVIEWER))
                .thenReturn(Optional.of(existing));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.addRole(conference, user, CommitteeRole.REVIEWER, null));
    }

    @Test
    void getCommitteeForConferenceDelegatesToRepository() {
        List<ConferenceCommitteeRole> roles = List.of(new ConferenceCommitteeRole());
        when(repository.findByConferenceId(1L)).thenReturn(roles);

        assertThat(service.getCommitteeForConference(conference)).isEqualTo(roles);
    }
}
