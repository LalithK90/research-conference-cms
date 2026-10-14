package org.confcms.cms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

// Kept off ConferenceCmsApplication itself (a @SpringBootApplication class) so @WebMvcTest's
// slicing -- which only pulls in @Controller/@ControllerAdvice/web-layer beans plus whatever a
// test explicitly @Imports -- doesn't have to build a real JPA metamodel just to satisfy an
// @EnableJpaAuditing declared on the one class every test slice's @SpringBootConfiguration
// lookup always finds.
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
