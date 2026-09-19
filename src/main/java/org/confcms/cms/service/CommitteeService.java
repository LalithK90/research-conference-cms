package org.confcms.cms.service;

import org.confcms.cms.domain.CommitteeRole;
import org.confcms.cms.domain.Conference;
import org.confcms.cms.domain.ConferenceCommitteeRole;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.ConferenceCommitteeRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CommitteeService {

    private final ConferenceCommitteeRoleRepository repository;

    public boolean hasRole(User user, Conference conference, CommitteeRole role) {
        return repository.findByConferenceIdAndUserIdAndRole(conference.getId(), user.getId(), role).isPresent();
    }

    public boolean isChairOrCoChair(User user, Conference conference) {
        return hasRole(user, conference, CommitteeRole.CHAIR) || hasRole(user, conference, CommitteeRole.CO_CHAIR);
    }

    public boolean hasAnyCommitteeRole(User user, Conference conference) {
        return repository.existsByConferenceIdAndUserId(conference.getId(), user.getId());
    }

    public List<ConferenceCommitteeRole> getCommitteeForConference(Conference conference) {
        return repository.findByConferenceId(conference.getId());
    }

    @Transactional
    public ConferenceCommitteeRole assignChair(Conference conference, User user) {
        repository.findByConferenceIdAndRole(conference.getId(), CommitteeRole.CHAIR)
                .ifPresent(repository::delete);

        ConferenceCommitteeRole chairRole = new ConferenceCommitteeRole();
        chairRole.setConference(conference);
        chairRole.setUser(user);
        chairRole.setRole(CommitteeRole.CHAIR);
        return repository.save(chairRole);
    }

    @Transactional
    public ConferenceCommitteeRole addRole(Conference conference, User user, CommitteeRole role, String displayTitle) {
        repository.findByConferenceIdAndUserIdAndRole(conference.getId(), user.getId(), role)
                .ifPresent(existing -> {
                    throw new IllegalStateException("This person already holds that role on this conference");
                });

        ConferenceCommitteeRole committeeRole = new ConferenceCommitteeRole();
        committeeRole.setConference(conference);
        committeeRole.setUser(user);
        committeeRole.setRole(role);
        committeeRole.setDisplayTitle(displayTitle);
        return repository.save(committeeRole);
    }

    @Transactional
    public void removeRole(Long conferenceCommitteeRoleId) {
        repository.deleteById(conferenceCommitteeRoleId);
    }
}
