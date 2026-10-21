package org.confcms.cms.security;

import org.confcms.cms.accesslog.AccessLogService;
import org.confcms.cms.config.SecurityConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// Shared setup for any @WebMvcTest that needs the REAL SecurityFilterChain (SecurityConfig's
// URL rules + a controller's own @PreAuthorize) to actually run, not just the annotation present
// in source. Extend this class rather than copying its pieces -- see
// AdminUserControllerSecurityTest/AdminConferenceControllerSecurityTest for the @WebMvcTest
// annotations each still needs on top (controllers, excludeAutoConfiguration, @Import).
//
// Three things have to be true before a slice like this will even start, let alone enforce
// security correctly:
// 1. SecurityConfig is a bare @Configuration (not a controller/filter/converter), so
//    @WebMvcTest's slice doesn't auto-include it -- subclasses must @Import(SecurityConfig.class).
// 2. Having a mocked ClientRegistrationRepository bean in context is enough for Boot's own
//    OAuth2ClientWebSecurityAutoConfiguration to try building ITS OWN SecurityFilterChain,
//    conflicting with this app's one custom chain -- subclasses must exclude it via
//    @WebMvcTest(excludeAutoConfiguration = OAuth2ClientWebSecurityAutoConfiguration.class).
// 3. spring-security-test's springSecurity() MockMvc configurer (which makes @WithMockUser's
//    authentication actually apply to requests through the real filter chain) isn't
//    auto-registered by @WebMvcTest either -- a known Spring Boot 4 webmvc-test gap. Registered
//    below via a MockMvcBuilderCustomizer bean (the documented, supported opt-in).
public abstract class AdminWebMvcSecurityTestSupport {


    @TestConfiguration
    public static class SpringSecurityMockMvcConfig {
        @Bean
        MockMvcBuilderCustomizer securityMockMvcCustomizer() {
            return builder -> builder.apply(SecurityMockMvcConfigurers.springSecurity());
        }
    }

    // SecurityConfig's own dependencies -- not part of the web slice, but SecurityConfig IS
    // picked up once @Import'd, so its constructor needs all of these satisfied for the
    // context to start.
    @MockitoBean
    protected CustomUserDetailsService customUserDetailsService;
    @MockitoBean
    protected MagicLinkAuthenticationProvider magicLinkAuthenticationProvider;
    @MockitoBean
    protected ClientRegistrationRepository clientRegistrationRepository;
    @MockitoBean
    protected CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean
    protected PasswordPromptAuthenticationSuccessHandler passwordPromptAuthenticationSuccessHandler;
    @MockitoBean
    protected OAuth2LoginFailureHandler oAuth2LoginFailureHandler;
    @MockitoBean
    protected LoginFailureHandler loginFailureHandler;
    @MockitoBean
    protected AccessLogService accessLogService;
}
