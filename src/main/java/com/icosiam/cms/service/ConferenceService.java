package com.icosiam.cms.service;

import com.icosiam.cms.domain.Conference;
import com.icosiam.cms.repository.ConferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConferenceService {

    private final ConferenceRepository conferenceRepository;

    @Transactional(readOnly = true)
    public Conference getActiveConference() {
        return conferenceRepository.findByIsActiveTrue()
                .orElseThrow(() -> new IllegalStateException("No active conference found"));
    }

    @Transactional
    public Conference saveConference(Conference conference) {
        // If setting this as active, deactivate others
        if (conference.isActive()) {
            conferenceRepository.findByIsActiveTrue()
                    .ifPresent(c -> {
                        if (!c.getId().equals(conference.getId())) {
                            c.setActive(false);
                            conferenceRepository.save(c);
                        }
                    });
        }
        return conferenceRepository.save(conference);
    }
}
