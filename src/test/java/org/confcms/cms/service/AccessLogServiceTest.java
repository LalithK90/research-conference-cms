package org.confcms.cms.service;

import org.confcms.cms.domain.AccessEventType;
import org.confcms.cms.domain.AccessLog;
import org.confcms.cms.user.User;
import org.confcms.cms.repository.AccessLogRepository;
import org.confcms.cms.submission.domain.PaperVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
}
