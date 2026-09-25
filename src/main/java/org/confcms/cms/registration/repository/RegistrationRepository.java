package org.confcms.cms.registration.repository;

import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.domain.Registration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RegistrationRepository extends JpaRepository<Registration, Long> {
    List<Registration> findByUserId(Long userId);
    List<Registration> findByConferenceIdAndPaymentStatus(Long conferenceId, PaymentStatus paymentStatus);
    Optional<Registration> findByUserIdAndConferenceIdAndPaymentStatusNot(Long userId, Long conferenceId, PaymentStatus paymentStatus);
}
