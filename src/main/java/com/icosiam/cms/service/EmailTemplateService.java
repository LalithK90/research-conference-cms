package com.icosiam.cms.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private final SpringTemplateEngine templateEngine;

    public String renderTemplate(String templateName, Map<String, Object> model) {
        Context ctx = new Context();
        if (model != null) ctx.setVariables(model);
        // templateName should be relative to `templates/`, e.g. `email/submission_confirmation.txt`
        return templateEngine.process(templateName, ctx);
    }
}
