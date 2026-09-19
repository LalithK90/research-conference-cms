package org.confcms.cms.registration.service;

import org.confcms.cms.domain.User;
import org.confcms.cms.registration.domain.PaymentStatus;
import org.confcms.cms.registration.domain.Registration;
import org.confcms.cms.registration.repository.RegistrationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RegistrationService {

    // ponytail: hardcoded ticket prices, move to per-conference config when
    // conference-level ticket pricing is designed as its own feature.
    private static final Map<String, BigDecimal> TICKET_PRICES = Map.of(
            "REGULAR", new BigDecimal("100"),
            "STUDENT", new BigDecimal("50")
    );

    private final RegistrationRepository registrationRepository;

    @Transactional
    public Registration register(User user, String ticketType) {
        BigDecimal amount = TICKET_PRICES.get(ticketType);
        if (amount == null) {
            throw new IllegalArgumentException("Unknown ticket type: " + ticketType);
        }

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
