package org.confcms.cms.repository;

import org.confcms.cms.domain.ConferencePaymentConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConferencePaymentConfigRepository extends JpaRepository<ConferencePaymentConfig, Long> {
}
