package org.confcms.cms.accesslog;

import org.confcms.cms.user.User;
import org.confcms.cms.service.GeoLocationService;
import org.confcms.cms.paper.PaperVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessLogServiceTest {

    @Mock
    private AccessLogRepository accessLogRepository;
    @Mock
    private GeoLocationService geoLocationService;

    private AccessLogService service;

    @BeforeEach
    void setUp() {
        service = new AccessLogService(accessLogRepository, geoLocationService);
    }

    @Test
    void logLoginWritesALoginEntryWithResolvedLocation() {
        User user = new User();
        user.setId(1L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.5");
        when(geoLocationService.resolveLocation("203.0.113.5")).thenReturn(Optional.of("Bangkok, Thailand"));

        service.logLogin(user, request);

        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(accessLogRepository).save(captor.capture());
        AccessLog saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(AccessEventType.LOGIN);
        assertThat(saved.getUser()).isEqualTo(user);
        assertThat(saved.getIpAddress()).isEqualTo("203.0.113.5");
        assertThat(saved.getResolvedLocation()).isEqualTo("Bangkok, Thailand");
        assertThat(saved.getPaperVersion()).isNull();
    }

    @Test
    void logLoginStoresNullLocationWhenResolutionFails() {
        User user = new User();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        when(geoLocationService.resolveLocation("127.0.0.1")).thenReturn(Optional.empty());

        service.logLogin(user, request);

        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(accessLogRepository).save(captor.capture());
        assertThat(captor.getValue().getResolvedLocation()).isNull();
    }

    @Test
    void logDownloadWritesAPaperDownloadEntryWithTheGivenVersion() {
        User user = new User();
        PaperVersion version = new PaperVersion();
        version.setId(9L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.5");
        when(geoLocationService.resolveLocation("203.0.113.5")).thenReturn(Optional.empty());

        service.logDownload(user, version, request);

        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(accessLogRepository).save(captor.capture());
        AccessLog saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(AccessEventType.PAPER_DOWNLOAD);
        assertThat(saved.getPaperVersion()).isEqualTo(version);
    }

    @Test
    void logLoginDoesNotPropagateARepositorySaveFailure() {
        User user = new User();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.5");
        when(geoLocationService.resolveLocation("203.0.113.5")).thenReturn(Optional.empty());
        doThrow(new RuntimeException("DB unavailable")).when(accessLogRepository).save(any());

        // Must not throw -- an audit-log failure must never break the action being audited.
        service.logLogin(user, request);
    }

    @Test
    void logMagicLinkRequestWritesAnEntryWithTheRequestedEmailAndNoUser() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.5");
        when(geoLocationService.resolveLocation("203.0.113.5")).thenReturn(Optional.of("Bangkok, Thailand"));

        service.logMagicLinkRequest("someone@example.com", request, true);

        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(accessLogRepository).save(captor.capture());
        AccessLog saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo(AccessEventType.MAGIC_LINK_REQUEST);
        assertThat(saved.getRequestedEmail()).isEqualTo("someone@example.com");
        assertThat(saved.getUser()).isNull();
        assertThat(saved.getIpAddress()).isEqualTo("203.0.113.5");
        assertThat(saved.getResolvedLocation()).isEqualTo("Bangkok, Thailand");
        assertThat(saved.isEmailSent()).isTrue();
    }

    @Test
    void logMagicLinkRequestRecordsEmailSentFalseWhenNoRealEmailWasSent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.5");
        when(geoLocationService.resolveLocation("203.0.113.5")).thenReturn(Optional.empty());

        service.logMagicLinkRequest("someone@example.com", request, false);

        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(accessLogRepository).save(captor.capture());
        assertThat(captor.getValue().isEmailSent()).isFalse();
    }

    @Test
    void wasMagicLinkRequestedRecentlyReturnsTrueWhenTheLastRequestIsInsideTheWindow() {
        AccessLog recent = new AccessLog();
        recent.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        when(accessLogRepository.findTopByEventTypeAndRequestedEmailIgnoreCaseAndIpAddressOrderByCreatedAtDesc(
                AccessEventType.MAGIC_LINK_REQUEST, "someone@example.com", "203.0.113.5"))
                .thenReturn(Optional.of(recent));

        boolean result = service.wasMagicLinkRequestedRecently(
                "someone@example.com", "203.0.113.5", Duration.ofMinutes(30));

        assertThat(result).isTrue();
    }

    @Test
    void wasMagicLinkRequestedRecentlyReturnsFalseWhenTheLastRequestIsOutsideTheWindow() {
        AccessLog stale = new AccessLog();
        stale.setCreatedAt(LocalDateTime.now().minusMinutes(45));
        when(accessLogRepository.findTopByEventTypeAndRequestedEmailIgnoreCaseAndIpAddressOrderByCreatedAtDesc(
                AccessEventType.MAGIC_LINK_REQUEST, "someone@example.com", "203.0.113.5"))
                .thenReturn(Optional.of(stale));

        boolean result = service.wasMagicLinkRequestedRecently(
                "someone@example.com", "203.0.113.5", Duration.ofMinutes(30));

        assertThat(result).isFalse();
    }

    @Test
    void wasMagicLinkRequestedRecentlyReturnsFalseWhenThereIsNoPriorRequest() {
        when(accessLogRepository.findTopByEventTypeAndRequestedEmailIgnoreCaseAndIpAddressOrderByCreatedAtDesc(
                AccessEventType.MAGIC_LINK_REQUEST, "someone@example.com", "203.0.113.5"))
                .thenReturn(Optional.empty());

        boolean result = service.wasMagicLinkRequestedRecently(
                "someone@example.com", "203.0.113.5", Duration.ofMinutes(30));

        assertThat(result).isFalse();
    }

    @Test
    void wasMagicLinkRequestedRecentlyDoesNotMatchTheSameEmailFromADifferentIp() {
        // Scoping by (email, IP) is what prevents an attacker who knows a victim's email
        // from locking the victim out of their own requests by submitting from elsewhere.
        when(accessLogRepository.findTopByEventTypeAndRequestedEmailIgnoreCaseAndIpAddressOrderByCreatedAtDesc(
                AccessEventType.MAGIC_LINK_REQUEST, "victim@example.com", "203.0.113.5"))
                .thenReturn(Optional.empty());

        boolean result = service.wasMagicLinkRequestedRecently(
                "victim@example.com", "203.0.113.5", Duration.ofMinutes(30));

        assertThat(result).isFalse();
    }

    @Test
    void hasReachedMagicLinkEmailCapReturnsTrueWhenSentCountMeetsTheMax() {
        when(accessLogRepository.countByEventTypeAndRequestedEmailIgnoreCaseAndEmailSentTrueAndCreatedAtAfter(
                eq(AccessEventType.MAGIC_LINK_REQUEST), eq("victim@example.com"), any(LocalDateTime.class)))
                .thenReturn(5L);

        boolean result = service.hasReachedMagicLinkEmailCap("victim@example.com", 5, Duration.ofMinutes(30));

        assertThat(result).isTrue();
    }

    @Test
    void hasReachedMagicLinkEmailCapReturnsFalseWhenSentCountIsBelowTheMax() {
        when(accessLogRepository.countByEventTypeAndRequestedEmailIgnoreCaseAndEmailSentTrueAndCreatedAtAfter(
                eq(AccessEventType.MAGIC_LINK_REQUEST), eq("victim@example.com"), any(LocalDateTime.class)))
                .thenReturn(4L);

        boolean result = service.hasReachedMagicLinkEmailCap("victim@example.com", 5, Duration.ofMinutes(30));

        assertThat(result).isFalse();
    }

    @Test
    void hasReachedMagicLinkEmailCapIsNotScopedByIp() {
        // This is the gap the per-(email, IP) check alone leaves open: the cap counts real
        // sends to one email across ALL requesting IPs combined, so an attacker rotating
        // through multiple IPs can't bypass it just by changing IP.
        when(accessLogRepository.countByEventTypeAndRequestedEmailIgnoreCaseAndEmailSentTrueAndCreatedAtAfter(
                eq(AccessEventType.MAGIC_LINK_REQUEST), eq("victim@example.com"), any(LocalDateTime.class)))
                .thenReturn(5L);

        boolean result = service.hasReachedMagicLinkEmailCap("victim@example.com", 5, Duration.ofMinutes(30));

        assertThat(result).isTrue();
        verify(accessLogRepository).countByEventTypeAndRequestedEmailIgnoreCaseAndEmailSentTrueAndCreatedAtAfter(
                eq(AccessEventType.MAGIC_LINK_REQUEST), eq("victim@example.com"), any(LocalDateTime.class));
    }
}
