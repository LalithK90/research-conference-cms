package org.confcms.cms.security;

import org.confcms.cms.accesslog.AccessLogService;
import org.confcms.cms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Collections;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final AccessLogService accessLogService;

    @Value("${app.auth.login.max-failures:5}")
    private int maxLoginFailures;

    @Value("${app.auth.login.lockout-window-minutes:15}")
    private int lockoutWindowMinutes;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // Checked (and the same way) whether or not the email belongs to a registered account --
        // skipping this check for an unknown email would make lockout itself a user-enumeration
        // oracle, same reasoning as the magic-link throttle in AccessLogService.
        if (accessLogService.isLoginLockedOut(email, maxLoginFailures, lockoutWindow())) {
            throw new LockedException("Too many failed login attempts; try again later");
        }

        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPasswordHash() != null ? user.getPasswordHash() : "",
                user.isEnabled(),
                true, true, true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }

    private Duration lockoutWindow() {
        return Duration.ofMinutes(lockoutWindowMinutes);
    }
}
