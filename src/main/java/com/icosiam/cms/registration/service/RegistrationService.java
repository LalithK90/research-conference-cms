package com.icosiam.cms.registration.service;

import com.icosiam.cms.domain.User;
import com.icosiam.cms.registration.domain.PaymentStatus;
import com.icosiam.cms.registration.domain.Registration;
import com.icosiam.cms.registration.repository.RegistrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final RegistrationRepository registrationRepository;

    @Transactional
    public Registration register(User user, String ticketType, BigDecimal amount) {
        Registration registration = new Registration();
        registration.setUser(user);
        registration.setTicketType(ticketType);
        registration.setAmount(amount);
        registration.setPaymentStatus(PaymentStatus.PENDING);
        
        return registrationRepository.save(registration);
    }

    @Transactional
    public void markAsPaid(Long registrationId) {
        Registration registration = registrationRepository.findById(registrationId)
                .orElseThrow(() -> new IllegalArgumentException("Registration not found"));
        registration.setPaymentStatus(PaymentStatus.PAID);
        registrationRepository.save(registration);
    }
}
