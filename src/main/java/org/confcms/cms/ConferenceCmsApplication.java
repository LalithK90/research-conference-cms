package org.confcms.cms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @SpringBootApplication already implies @ComponentScan + @EnableAutoConfiguration (which
// includes JpaRepositoriesAutoConfiguration/EntityScan-equivalent behavior) scanning from this
// class's own package (org.confcms.cms) down -- exactly what the previously-explicit
// @EntityScan/@ComponentScan/@EnableJpaRepositories here duplicated. That duplicate scan wasn't
// harmless: its exclude filters (meant to skip core/proceedings/web.config, a leftover from a
// since-removed duplicate @SpringBootApplication entry point) only applied to the EXPLICIT scan,
// never to @SpringBootApplication's own implicit one running alongside it -- so the exclusions
// silently never took effect, and @WebMvcTest's own slice filtering had two competing scans to
// contend with instead of one. Removing the explicit annotations leaves exactly one scan.
// @EnableJpaAuditing moved to config.JpaAuditingConfig for the same reason -- it was the next
// thing a test slice's @SpringBootConfiguration lookup (which always finds THIS class) pulled
// in regardless of scope.
@SpringBootApplication
public class ConferenceCmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConferenceCmsApplication.class, args);
    }
}
