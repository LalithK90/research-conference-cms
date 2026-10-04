package org.confcms.cms.conference;

import org.confcms.cms.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

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

    // One query for every conference this user chairs or co-chairs, as a Set for O(1)
    // membership checks against a list of papers that may span many conferences -- use this
    // instead of calling isChairOrCoChair once per paper, which is 2 queries per paper (one
    // for each role) and doesn't scale past a handful of papers.
    public Set<Long> getChairOrCoChairConferenceIds(User user) {
        return Set.copyOf(repository.findChairOrCoChairConferenceIds(user.getId()));
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
