package org.confcms.cms.conference;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConferencePaymentConfigRepository extends JpaRepository<ConferencePaymentConfig, Long> {
}
