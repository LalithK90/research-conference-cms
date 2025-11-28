package com.icosiam.cms.repository;

import com.icosiam.cms.domain.ConferencePaymentConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConferencePaymentConfigRepository extends JpaRepository<ConferencePaymentConfig, Long> {
}
