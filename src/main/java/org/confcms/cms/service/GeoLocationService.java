package org.confcms.cms.service;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.CityResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Optional;

@Service
public class GeoLocationService {

    private static final Logger log = LoggerFactory.getLogger(GeoLocationService.class);

    private final DatabaseReader reader;

    public GeoLocationService() {
        DatabaseReader loaded = null;
        try (InputStream in = getClass().getResourceAsStream("/geoip/GeoLite2-City.mmdb")) {
            if (in == null) {
                log.warn("GeoLite2-City.mmdb not found on classpath; login location resolution disabled");
            } else {
                loaded = new DatabaseReader.Builder(in).build();
            }
        } catch (IOException e) {
            log.warn("Failed to load GeoLite2-City.mmdb; login location resolution disabled", e);
        }
        this.reader = loaded;
    }

    public Optional<String> resolveLocation(String ipAddress) {
        if (reader == null) {
            return Optional.empty();
        }
        try {
            InetAddress address = InetAddress.getByName(ipAddress);
            CityResponse response = reader.city(address);
            String city = response.getCity().getName();
            String country = response.getCountry().getName();
            if (city == null && country == null) {
                return Optional.empty();
            }
            return Optional.of((city != null ? city + ", " : "") + (country != null ? country : ""));
        } catch (GeoIp2Exception | IOException e) {
            // Includes AddressNotFoundException for private/loopback/unresolvable IPs -- expected
            // and common in local development, not an error worth logging at WARN level.
            return Optional.empty();
        }
    }
}
