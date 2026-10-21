package org.confcms.cms.conference;

import org.confcms.cms.config.SecurityConfig;
import org.confcms.cms.security.AdminWebMvcSecurityTestSupport;
import org.confcms.cms.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Same purpose as AdminUserControllerSecurityTest -- proves /admin/conference/** (including
// payment-provider configuration) is actually rejected for a non-admin through the real
// SecurityFilterChain. See AdminWebMvcSecurityTestSupport for why this slice needs
// @Import(SecurityConfig.class), an excluded OAuth2ClientWebSecurityAutoConfiguration, and the
// inherited springSecurity() MockMvc setup.
@WebMvcTest(controllers = AdminConferenceController.class, excludeAutoConfiguration =
        org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration.class)
@Import({SecurityConfig.class, AdminWebMvcSecurityTestSupport.SpringSecurityMockMvcConfig.class})
class AdminConferenceControllerSecurityTest extends AdminWebMvcSecurityTestSupport {

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
    void anonymousIsRedirectedToLoginFromTheConferenceList() throws Exception {
        mockMvc.perform(get("/admin/conference/list"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanReachTheNewConferenceForm() throws Exception {
        when(userRepository.findAll()).thenReturn(Collections.emptyList());
        when(conferenceRepository.findAll()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/conference/new"))
                .andExpect(status().isOk());
    }
}
