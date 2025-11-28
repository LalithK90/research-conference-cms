package com.icosiam.cms.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "conference_payment_configs")
@Getter
@Setter
public class ConferencePaymentConfig extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentProvider provider = PaymentProvider.FREE;

    // Stripe
    private String stripePublishableKey;
    private String stripeSecretKey;

    // PayPal
    private String paypalClientId;
    private String paypalClientSecret;

    // Local Bank
    @Column(columnDefinition = "TEXT")
    private String bankDetails; // Instructions or JSON config for local bank

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conference_id", nullable = false)
    private Conference conference;
}
