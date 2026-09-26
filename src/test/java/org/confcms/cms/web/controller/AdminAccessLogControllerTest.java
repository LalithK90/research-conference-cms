package org.confcms.cms.web.controller;

import org.confcms.cms.domain.AccessLog;
import org.confcms.cms.repository.AccessLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAccessLogControllerTest {

    @Mock
    private AccessLogRepository accessLogRepository;

    private AdminAccessLogController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminAccessLogController(accessLogRepository);
    }

    @Test
    void listReturnsTheAccessLogsView() {
        List<AccessLog> logs = List.of(new AccessLog());
        when(accessLogRepository.findTop100ByOrderByCreatedAtDesc()).thenReturn(logs);

        Model model = new ExtendedModelMap();
        String view = controller.list(model);

        assertThat(view).isEqualTo("admin/access_logs");
        assertThat(model.getAttribute("logs")).isEqualTo(logs);
    }
}
