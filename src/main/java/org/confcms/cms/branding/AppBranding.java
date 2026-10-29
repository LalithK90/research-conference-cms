package org.confcms.cms.branding;

import org.confcms.cms.core.domain.BaseEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

// Single global row (id=1, enforced by AppBrandingService) -- not per-conference. This app
// supports multiple conferences in one install, but is sold as one branded instance per
// customer, so favicon/logo/colors are an install-wide setting, not tied to any one
// Conference record.
@Entity
@Table(name = "app_branding")
@Getter
@Setter
public class AppBranding extends BaseEntity {

    private String faviconUrl;
    private String logoUrl;
    private String primaryColor;
    private String primaryColorDark;
    private String accentColor;
}
