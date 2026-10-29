package org.confcms.cms.branding;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AppBrandingRepository extends JpaRepository<AppBranding, Long> {
}
