package org.confcms.cms.auth;

import org.confcms.cms.accesslog.AccessLogService;
import org.confcms.cms.config.SecurityConfig;
import org.confcms.cms.security.CustomOAuth2UserService;
import org.confcms.cms.security.CustomUserDetailsService;
import org.confcms.cms.security.LoginFailureHandler;
import org.confcms.cms.security.MagicLinkAuthenticationProvider;
import org.confcms.cms.security.OAuth2LoginFailureHandler;
import org.confcms.cms.security.PasswordPromptAuthenticationSuccessHandler;
import org.confcms.cms.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Proves /admin/users/** is actually rejected for a non-admin through the REAL
// SecurityFilterChain (SecurityConfig's URL rule + AdminUserController's @PreAuthorize), not
// just that the annotation is present in source.
//
// Three things had to be true before this slice would even start, let alone enforce security
// correctly:
// 1. @WebMvcTest(AdminUserController.class) alone used to pull in EVERY @Controller app-wide,
//    each needing its own JPA repositories, which then failed with no EntityManagerFactory in
//    this slice. Root cause: ConferenceCmsApplication had a duplicate, exclude-filter-defeating
//    @ComponentScan alongside @SpringBootApplication's own implicit one (removed -- see that
//    class's comment), so there's now exactly one scan for @WebMvcTest to slice correctly.
// 2. SecurityConfig is a bare @Configuration (not a controller/filter/converter), so @WebMvcTest's
//    slice still doesn't auto-include it -- @Import(SecurityConfig.class) adds it explicitly.
// 3. Having a mocked ClientRegistrationRepository bean in context is enough for Boot's own
//    OAuth2ClientWebSecurityAutoConfiguration to try building ITS OWN SecurityFilterChain,
//    conflicting with this app's one custom chain -- excluded explicitly below.
// 4. spring-security-test's springSecurity() MockMvc configurer (which makes @WithMockUser's
//    authentication actually apply to requests through the real filter chain) isn't
//    auto-registered by this slice either -- a known Spring Boot 4 webmvc-test gap. Registered
//    explicitly via a MockMvcBuilderCustomizer bean (the documented, supported opt-in).
@WebMvcTest(controllers = AdminUserController.class, excludeAutoConfiguration =
        org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration.class)
@Import({SecurityConfig.class, AdminUserControllerSecurityTest.SpringSecurityMockMvcConfig.class})
class AdminUserControllerSecurityTest {

    // Spring Boot's own bridge that auto-applies spring-security-test's springSecurity()
    // MockMvc configurer whenever a SecurityFilterChain bean is present isn't picked up by this
    // slice (a known Spring Boot 4 webmvc-test gap) -- registering it explicitly as a
    // MockMvcBuilderCustomizer is the documented, supported way to opt in manually.
    @TestConfiguration
    static class SpringSecurityMockMvcConfig {
        @Bean
        MockMvcBuilderCustomizer securityMockMvcCustomizer() {
            return builder ->
                    builder.apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity());
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private UserRepository userRepository;

    // SecurityConfig's own dependencies -- not part of the web slice, but SecurityConfig IS
    // picked up (it's an @EnableWebSecurity @Configuration), so its constructor needs all of
    // these satisfied for the context to start.
    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;
    @MockitoBean
    private MagicLinkAuthenticationProvider magicLinkAuthenticationProvider;
    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;
    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;
    @MockitoBean
    private PasswordPromptAuthenticationSuccessHandler passwordPromptAuthenticationSuccessHandler;
    @MockitoBean
    private OAuth2LoginFailureHandler oAuth2LoginFailureHandler;
    @MockitoBean
    private LoginFailureHandler loginFailureHandler;
    @MockitoBean
    private AccessLogService accessLogService;

    @Test
    @WithMockUser(roles = "AUTHOR")
    void nonAdminIsRejectedFromTheUserListPage() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "REVIEWER")
    void reviewerIsRejectedFromPromotingAUserToAdmin() throws Exception {
        mockMvc.perform(post("/admin/users/1/role").with(csrf()).param("role", "ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsRejectedFromTheUserListPage() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanReachTheUserListPage() throws Exception {
        when(userRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isOk());
    }
}
