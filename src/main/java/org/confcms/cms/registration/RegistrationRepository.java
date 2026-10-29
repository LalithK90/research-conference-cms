package org.confcms.cms.registration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RegistrationRepository extends JpaRepository<Registration, Long> {
    List<Registration> findByUserId(Long userId);
    List<Registration> findByConferenceIdAndPaymentStatus(Long conferenceId, PaymentStatus paymentStatus);
    Optional<Registration> findByUserIdAndConferenceIdAndPaymentStatusNot(Long userId, Long conferenceId, PaymentStatus paymentStatus);

    // Used by the admin registrations list, which renders reg.user.fullName/email for every
    // row -- plain findByConferenceIdAndPaymentStatus left user as a lazy load, firing one
    // extra query per registration. JOIN FETCH pulls it in the one query instead.
    @Query("SELECT r FROM Registration r JOIN FETCH r.user WHERE r.conference.id = :conferenceId AND r.paymentStatus = :paymentStatus")
    List<Registration> findByConferenceIdAndPaymentStatusWithUser(Long conferenceId, PaymentStatus paymentStatus);
}
