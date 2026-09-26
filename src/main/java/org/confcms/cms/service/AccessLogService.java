package org.confcms.cms.service;

import org.confcms.cms.domain.AccessEventType;
import org.confcms.cms.domain.AccessLog;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.AccessLogRepository;
import org.confcms.cms.submission.domain.PaperVersion;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccessLogService {

    private static final Logger log = LoggerFactory.getLogger(AccessLogService.class);

    private final AccessLogRepository accessLogRepository;
    private final GeoLocationService geoLocationService;

    @Transactional
    public void logLogin(User user, HttpServletRequest request) {
        write(AccessEventType.LOGIN, user, request, null);
    }

    @Transactional
    public void logDownload(User user, PaperVersion paperVersion, HttpServletRequest request) {
        write(AccessEventType.PAPER_DOWNLOAD, user, request, paperVersion);
    }

    private void write(AccessEventType eventType, User user, HttpServletRequest request, PaperVersion paperVersion) {
        try {
            String ip = request.getRemoteAddr();
            AccessLog entry = new AccessLog();
            entry.setEventType(eventType);
            entry.setUser(user);
            entry.setIpAddress(ip);
            entry.setResolvedLocation(geoLocationService.resolveLocation(ip).orElse(null));
            entry.setPaperVersion(paperVersion);
            accessLogRepository.save(entry);
        } catch (Exception e) {
            // An audit-log write failure must never break the login or download it's auditing --
            // losing one log row is an acceptable gap; blocking a real user action to protect an
            // audit trail is not.
            log.warn("Failed to write access log entry (eventType={})", eventType, e);
        }
    }
}
