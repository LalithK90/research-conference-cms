package com.icosiam.cms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;
    private final EmailQueueService emailQueueService;
    private final EmailTemplateService emailTemplateService;

    // Enqueue a simple plain-text email for async sending
    public void sendSimpleEmail(String to, String subject, String text) {
        EmailMessage msg = new EmailMessage(to, subject, text);
        try {
            emailQueueService.sendEmailAsync(msg);
        } catch (Exception e) {
            // If async dispatch fails for some reason, fall back to synchronous send
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setTo(to);
                message.setSubject(subject);
                message.setText(text);
                mailSender.send(message);
            } catch (Exception ex) {
                log.warn("Failed to send email to {} - falling back to log. Reason: {}", to, ex.getMessage());
                log.info("Email fallback to {} | subject={} | body={}", to, subject, text);
            }
        }
    }

    public void sendTemplateEmail(String to, String subject, String templateName, java.util.Map<String, Object> model) {
        String body = emailTemplateService.renderTemplate(templateName, model);
        sendSimpleEmail(to, subject, body);
    }
}
