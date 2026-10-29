package org.confcms.cms.branding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppBrandingServiceTest {

    @Mock
    private AppBrandingRepository repository;

    private AppBrandingService service;

    @BeforeEach
    void setUp() {
        service = new AppBrandingService(repository);
    }

    @Test
    void getReturnsTheExistingRowWhenOnePresent() {
        AppBranding existing = new AppBranding();
        existing.setId(1L);
        existing.setPrimaryColor("#123456");
        when(repository.findAll()).thenReturn(List.of(existing));

        AppBranding result = service.get();

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any());
    }

    // First boot: no row exists yet. Rather than every caller (every page render) needing a
    // null-check, get() creates and persists the single row on first access so callers always
    // get a real object with all-null (= "use the built-in defaults") fields.
    @Test
    void getCreatesAndPersistsARowWhenNonePresent() {
        when(repository.findAll()).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppBranding result = service.get();

        assertThat(result).isNotNull();
        verify(repository).save(any(AppBranding.class));
    }

    @Test
    void saveUpdatesTheExistingSingletonRowInsteadOfInsertingASecondOne() {
        AppBranding existing = new AppBranding();
        existing.setId(1L);
        when(repository.findAll()).thenReturn(List.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppBranding form = new AppBranding();
        form.setPrimaryColor("#abcdef");
        form.setFaviconUrl("https://example.com/favicon.ico");

        AppBranding result = service.save(form);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getPrimaryColor()).isEqualTo("#abcdef");
        assertThat(result.getFaviconUrl()).isEqualTo("https://example.com/favicon.ico");
    }

    // Clearing a field in the admin form submits "" (an HTML input's empty value), not null --
    // save() must store that as null so templates' faviconUrl != null fallback check works,
    // instead of rendering a broken href="" / src="".
    @Test
    void saveStoresBlankFieldsAsNullNotEmptyString() {
        AppBranding existing = new AppBranding();
        existing.setId(1L);
        existing.setFaviconUrl("https://example.com/old-favicon.ico");
        when(repository.findAll()).thenReturn(List.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppBranding form = new AppBranding();
        form.setFaviconUrl("");
        form.setLogoUrl("   ");

        AppBranding result = service.save(form);

        assertThat(result.getFaviconUrl()).isNull();
        assertThat(result.getLogoUrl()).isNull();
    }

    // Enforced here, not just in AdminBrandingController -- this is the real trust boundary.
    // These values render straight into a <style> block in every page's <head>, so a value
    // that breaks out of the CSS declaration (e.g. "red; } body { display: none !important; }")
    // would be live markup on every page, not just malformed data.
    @Test
    void saveRejectsAPrimaryColorThatIsNotHex() {
        AppBranding form = new AppBranding();
        form.setPrimaryColor("red; } body { display: none !important; } .x {");

        assertThatThrownBy(() -> service.save(form))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any(AppBranding.class));
    }

    @Test
    void saveRejectsAFaviconUrlWithAJavascriptScheme() {
        AppBranding form = new AppBranding();
        form.setFaviconUrl("javascript:alert(1)");

        assertThatThrownBy(() -> service.save(form))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any(AppBranding.class));
    }
}
