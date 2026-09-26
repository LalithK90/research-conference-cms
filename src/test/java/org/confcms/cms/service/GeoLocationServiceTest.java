package org.confcms.cms.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoLocationServiceTest {

    @Test
    void resolveLocationReturnsEmptyForALoopbackAddress() {
        GeoLocationService service = new GeoLocationService();

        assertThat(service.resolveLocation("127.0.0.1")).isEmpty();
    }

    @Test
    void resolveLocationReturnsEmptyForAPrivateAddress() {
        GeoLocationService service = new GeoLocationService();

        assertThat(service.resolveLocation("192.168.1.1")).isEmpty();
    }

    @Test
    void resolveLocationReturnsEmptyForAnUnresolvableHostname() {
        GeoLocationService service = new GeoLocationService();

        assertThat(service.resolveLocation("not-a-valid-address")).isEmpty();
    }
}
