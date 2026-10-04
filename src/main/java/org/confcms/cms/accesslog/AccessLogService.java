package org.confcms.cms.accesslog;

import org.confcms.cms.user.User;
import org.confcms.cms.service.GeoLocationService;
import org.confcms.cms.paper.PaperVersion;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccessLogService {

    private static final Logger log = LoggerFactory.getLogger(AccessLogService.class);

    private final AccessLogRepository accessLogRepository;
    private final GeoLocationService geoLocationService;

    // Deliberately NOT @Transactional: accessLogRepository.save() already runs in its own
    // transaction (Spring Data's default), and wrapping this method in one too means a save
    // failure marks THIS method's transaction rollback-only -- the catch below swallows the
    // exception locally, but the surrounding @Transactional proxy still throws
    // UnexpectedRollbackException on exit, which would defeat the whole point of this class
    // (never letting a log-write failure break the login/download it's auditing).
    public void logLogin(User user, HttpServletRequest request) {
        write(AccessEventType.LOGIN, user, request, null);
    }

    public void logDownload(User user, PaperVersion paperVersion, HttpServletRequest request) {
        write(AccessEventType.PAPER_DOWNLOAD, user, request, paperVersion);
    }

    // emailSent reflects the controller's final decision for THIS request (false for a
    // rate-limited repeat or an unknown email) -- recorded once, after that decision is made,
    // rather than logged first and updated after, so there's no separate write to reconcile.
    public void logMagicLinkRequest(String email, HttpServletRequest request, boolean emailSent) {
        try {
            String ip = request.getRemoteAddr();
            AccessLog entry = new AccessLog();
            entry.setEventType(AccessEventType.MAGIC_LINK_REQUEST);
            entry.setRequestedEmail(email);
            entry.setIpAddress(ip);
            entry.setResolvedLocation(geoLocationService.resolveLocation(ip).orElse(null));
            entry.setEmailSent(emailSent);
            accessLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("Failed to write access log entry (eventType=MAGIC_LINK_REQUEST)", e);
        }
    }

    // Checked (and logged) identically whether or not the email belongs to a registered
    // account -- rate-limiting only known emails would itself be a user-enumeration oracle.
    // Scoped by (email, requesterIp): scoping by email alone would let anyone who knows a
    // victim's email lock the real victim out of their own requests for the rest of the
    // window just by submitting that email first, from anywhere.
    public boolean wasMagicLinkRequestedRecently(String email, String requesterIp, java.time.Duration window) {
        return accessLogRepository
                .findTopByEventTypeAndRequestedEmailIgnoreCaseAndIpAddressOrderByCreatedAtDesc(
                        AccessEventType.MAGIC_LINK_REQUEST, email, requesterIp)
                .map(last -> last.getCreatedAt().isAfter(java.time.LocalDateTime.now().minus(window)))
                .orElse(false);
    }

    // Independent of the per-(email, IP) check above: caps total real sends to one address
    // across ALL requesting IPs combined, closing the gap an attacker rotating through
    // multiple IPs (a botnet/proxy chain) would otherwise exploit to email-bomb one victim.
    public boolean hasReachedMagicLinkEmailCap(String email, int maxSends, java.time.Duration window) {
        long sentCount = accessLogRepository.countByEventTypeAndRequestedEmailIgnoreCaseAndEmailSentTrueAndCreatedAtAfter(
                AccessEventType.MAGIC_LINK_REQUEST, email, java.time.LocalDateTime.now().minus(window));
        return sentCount >= maxSends;
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
