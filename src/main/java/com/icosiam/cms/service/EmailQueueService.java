package com.icosiam.cms.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailQueueService {

    private final JavaMailSender mailSender;

    @Async("emailExecutor")
    public void sendEmailAsync(EmailMessage message) {
        try {
            SimpleMailMessage msg = new SimpleMailMessage();
            msg.setTo(message.getTo());
            msg.setSubject(message.getSubject());
            msg.setText(message.getBody());
            mailSender.send(msg);
            log.info("Email sent to {} subject={}", message.getTo(), message.getSubject());
        } catch (Exception e) {
            log.warn("Failed to send email to {}. Falling back to log. Reason: {}", message.getTo(), e.getMessage());
            log.info("Email fallback to {} | subject={} | body={}", message.getTo(), message.getSubject(), message.getBody());
        }
    }
}
