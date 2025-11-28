package com.icosiam.cms.domain;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class Registration extends BaseEntity {

    private Conference conference;

    private User user;

    private String participantType; // Student, Academic, Industry

    private BigDecimal amount;

    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    private String invoicePath;
}
