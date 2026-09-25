package org.confcms.cms.domain;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentSecretConverterTest {

    private static String validKey() {
        byte[] keyBytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            keyBytes[i] = (byte) i;
        }
        return Base64.getEncoder().encodeToString(keyBytes);
    }

    @Test
    void encryptsAndDecryptsRoundTrip() {
        PaymentSecretConverter converter = new PaymentSecretConverter(validKey());

        String ciphertext = converter.convertToDatabaseColumn("sk_test_secret123");
        String plaintext = converter.convertToEntityAttribute(ciphertext);

        assertThat(ciphertext).isNotEqualTo("sk_test_secret123");
        assertThat(plaintext).isEqualTo("sk_test_secret123");
    }

    @Test
    void encryptingTheSamePlaintextTwiceProducesDifferentCiphertext() {
        PaymentSecretConverter converter = new PaymentSecretConverter(validKey());

        String first = converter.convertToDatabaseColumn("same-secret");
        String second = converter.convertToDatabaseColumn("same-secret");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void nullPlaintextConvertsToNull() {
        PaymentSecretConverter converter = new PaymentSecretConverter(validKey());

        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void nullStoredValueConvertsToNull() {
        PaymentSecretConverter converter = new PaymentSecretConverter(validKey());

        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    @Test
    void rejectsAKeyThatIsNotExactly32BytesAfterDecoding() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());

        assertThatThrownBy(() -> new PaymentSecretConverter(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
