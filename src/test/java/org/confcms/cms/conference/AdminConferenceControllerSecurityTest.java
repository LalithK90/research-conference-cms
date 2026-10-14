package org.confcms.cms.conference;

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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Same purpose as AdminUserControllerSecurityTest -- proves /admin/conference/** (including
// payment-provider configuration) is actually rejected for a non-admin through the real
// SecurityFilterChain. See that class's comment for why this slice needs @Import(SecurityConfig
// .class), an excluded OAuth2ClientWebSecurityAutoConfiguration, and an explicit
// MockMvcBuilderCustomizer registering springSecurity().
@WebMvcTest(controllers = AdminConferenceController.class, excludeAutoConfiguration =
        org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration.class)
@Import({SecurityConfig.class, AdminConferenceControllerSecurityTest.SpringSecurityMockMvcConfig.class})
class AdminConferenceControllerSecurityTest {

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
    private ConferenceService conferenceService;
    @MockitoBean
    private CommitteeService committeeService;
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private ConferenceRepository conferenceRepository;

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
    void nonAdminIsRejectedFromTheNewConferenceForm() throws Exception {
        mockMvc.perform(get("/admin/conference/new"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "REVIEWER")
    void reviewerIsRejectedFromSavingAConference() throws Exception {
        mockMvc.perform(post("/admin/conference/save").with(csrf())
                        .param("title", "Hijacked Conf")
                        .param("paymentProvider", "FREE")
                        .param("chairUserId", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsRejectedFromTheConferenceList() throws Exception {
        mockMvc.perform(get("/admin/conference/list"))
                .andExpect(status().is3xxRedirection());
    }
}
