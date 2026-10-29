package org.confcms.cms.web;

import org.confcms.cms.branding.AppBranding;
import org.confcms.cms.branding.AppBrandingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrandingModelAdviceTest {

    @Mock
    private AppBrandingService brandingService;

    private BrandingModelAdvice advice;

    @BeforeEach
    void setUp() {
        advice = new BrandingModelAdvice(brandingService);
    }

    @Test
    void brandingExposesWhateverTheServiceReturns() {
        AppBranding branding = new AppBranding();
        branding.setPrimaryColor("#06690f");
        when(brandingService.get()).thenReturn(branding);

        assertThat(advice.branding()).isSameAs(branding);
    }
}
