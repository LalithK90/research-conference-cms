package org.confcms.cms.auth;

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

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Proves /admin/users/** is actually rejected for a non-admin through the REAL
// SecurityFilterChain (SecurityConfig's URL rule + AdminUserController's @PreAuthorize), not
// just that the annotation is present in source. See AdminWebMvcSecurityTestSupport for why
// this slice needs @Import(SecurityConfig.class), an excluded
// OAuth2ClientWebSecurityAutoConfiguration, and the inherited springSecurity() MockMvc setup --
// also why @WebMvcTest(AdminUserController.class) alone works at all now (it used to pull in
// every @Controller app-wide; see ConferenceCmsApplication's comment for that fix).
@WebMvcTest(controllers = AdminUserController.class, excludeAutoConfiguration =
        org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration.class)
@Import({SecurityConfig.class, AdminWebMvcSecurityTestSupport.SpringSecurityMockMvcConfig.class})
class AdminUserControllerSecurityTest extends AdminWebMvcSecurityTestSupport {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private UserRepository userRepository;

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
    void anonymousIsRedirectedToLoginFromTheUserListPage() throws Exception {
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
