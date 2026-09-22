package org.confcms.cms.security;

import org.confcms.cms.core.security.Role;
import org.confcms.cms.domain.User;
import org.confcms.cms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
@RequiredArgsConstructor
public class FirstRunAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstRunAdminInitializer.class);
    private static final String ADMIN_EMAIL = "asakahatapitiya@gmail.com";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (!userRepository.findByRole(Role.ADMIN).isEmpty()) {
            return;
        }

        String generatedPassword = generateSecurePassword();

        User admin = new User();
        admin.setEmail(ADMIN_EMAIL);
        admin.setFullName("System Administrator");
        admin.setRole(Role.ADMIN);
        admin.setPasswordHash(passwordEncoder.encode(generatedPassword));
        admin.setEnabled(true);
        userRepository.save(admin);

        log.info("=====================================================");
        log.info(" GENERATED ADMIN ACCOUNT (save this now, shown once)");
        log.info(" Email:    {}", ADMIN_EMAIL);
        log.info(" Password: {}", generatedPassword);
        log.info("=====================================================");
    }

    private String generateSecurePassword() {
        byte[] randomBytes = new byte[24];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
