package org.confcms.cms.accesslog;

import org.confcms.cms.accesslog.AccessLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/access-logs")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminAccessLogController {

    private final AccessLogRepository accessLogRepository;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("logs", accessLogRepository.findTop100ByOrderByCreatedAtDesc());
        return "admin/access_logs";
    }
}
