package org.confcms.cms.branding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminBrandingControllerTest {

    @Mock
    private AppBrandingService brandingService;

    private AdminBrandingController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminBrandingController(brandingService);
    }

    @Test
    void formShowsTheCurrentBranding() {
        AppBranding branding = new AppBranding();
        branding.setId(1L);
        when(brandingService.get()).thenReturn(branding);

        Model model = new ExtendedModelMap();
        String view = controller.form(model);

        assertThat(view).isEqualTo("admin/branding");
        assertThat(model.getAttribute("branding")).isEqualTo(branding);
    }

    @Test
    void saveUpdatesBrandingAndRedirects() {
        AppBranding form = new AppBranding();
        form.setPrimaryColor("#123456");

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.save(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/branding");
        verify(brandingService).save(form);
        assertThat(redirectAttributes.getFlashAttributes()).containsKey("message");
    }

    @Test
    void saveRejectsAJavascriptSchemeFaviconUrl() {
        AppBranding form = new AppBranding();
        form.setFaviconUrl("javascript:alert(1)");

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.save(form, redirectAttributes);

        assertThat(view).isEqualTo("redirect:/admin/branding");
        assertThat((java.util.Map<String, Object>) redirectAttributes.getFlashAttributes())
                .containsKey("error");
        verify(brandingService, never()).save(any());
    }

    @Test
    void saveRejectsAJavascriptSchemeLogoUrl() {
        AppBranding form = new AppBranding();
        form.setLogoUrl("javascript:alert(1)");

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        controller.save(form, redirectAttributes);

        verify(brandingService, never()).save(any());
    }
}
