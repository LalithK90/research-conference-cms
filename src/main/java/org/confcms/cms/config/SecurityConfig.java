package org.confcms.cms.config;

import org.confcms.cms.security.CustomOAuth2UserService;
import org.confcms.cms.security.CustomUserDetailsService;
import org.confcms.cms.security.MagicLinkAuthenticationFilter;
import org.confcms.cms.security.MagicLinkAuthenticationProvider;
import org.confcms.cms.security.PasswordPromptAuthenticationSuccessHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration("securityConfigLegacy")
@Profile("!dev")
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final MagicLinkAuthenticationProvider magicLinkAuthenticationProvider;
    private final ClientRegistrationRepository clientRegistrationRepository;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final PasswordPromptAuthenticationSuccessHandler passwordPromptAuthenticationSuccessHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // Dedicated manager for the magic-link filter: the global AuthenticationManager from
        // AuthenticationConfiguration only knows about the DaoAuthenticationProvider wired via
        // UserDetailsService, not the magicLinkAuthenticationProvider registered on this chain's
        // own AuthenticationManagerBuilder (they are separate managers), so authenticate() would
        // throw ProviderNotFoundException. A minimal ProviderManager scoped to just this provider
        // is simpler and correct.
        AuthenticationManager magicLinkAuthenticationManager = new ProviderManager(magicLinkAuthenticationProvider);

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/home", "/about", "/committee", "/speakers", "/sponsors", "/call-for-papers", "/venue", "/contact", "/register", "/past-conferences/**", "/login", "/magic-link/**", "/auth/**", "/invitations/**").permitAll()
                .requestMatchers("/css/**", "/js/**", "/images/**", "/uploads/**").permitAll()
                .requestMatchers("/admin/conference/**").hasRole("ADMIN")
                .requestMatchers("/admin/users/**").hasRole("ADMIN")
                .requestMatchers("/review/**").hasRole("REVIEWER")
                .requestMatchers("/submission/**").hasAnyRole("AUTHOR", "ADMIN")
                .anyRequest().authenticated()
            )
            .authenticationProvider(magicLinkAuthenticationProvider)
            .addFilterBefore(new MagicLinkAuthenticationFilter(magicLinkAuthenticationManager, passwordPromptAuthenticationSuccessHandler), UsernamePasswordAuthenticationFilter.class)
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(passwordPromptAuthenticationSuccessHandler)
                .permitAll()
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/")
                .permitAll()
            )
            // A session that has timed out server-side (server.servlet.session.timeout=30m)
            // sends the next request's JSESSIONID cookie for a session Tomcat no longer has;
            // without this, Spring Security's default handling of that case isn't a clear
            // "you were logged out due to inactivity" redirect. invalidSessionUrl covers both
            // true inactivity-timeout and any other reason the session is no longer valid
            // (server restart, invalidated cookie), since the two aren't distinguishable here.
            .sessionManagement(session -> session
                .invalidSessionUrl("/login?expired")
            );

        // Only register oauth2Login when at least one provider (Google/ORCID) is actually
        // configured. InMemoryClientRegistrationRepository throws on construction for an empty
        // registrations list (see OAuth2ProviderConfig), so an empty-safe repository is used
        // instead when nothing is configured -- guard on that here rather than registering a
        // login mechanism that has no reachable providers.
        boolean anyOAuth2ProviderConfigured = clientRegistrationRepository.findByRegistrationId("google") != null
                || clientRegistrationRepository.findByRegistrationId("orcid") != null;
        if (anyOAuth2ProviderConfigured) {
            http.oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                .successHandler(passwordPromptAuthenticationSuccessHandler)
            );
        }

        return http.build();
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
