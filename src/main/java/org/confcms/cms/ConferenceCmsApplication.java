package org.confcms.cms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan(basePackages = "org.confcms.cms")
@ComponentScan(basePackages = "org.confcms.cms",
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "org\\.confcms\\.cms\\.(core|proceedings|web\\.config)\\..*"))
@EnableJpaAuditing
@EnableJpaRepositories(basePackages = "org.confcms.cms")
public class ConferenceCmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConferenceCmsApplication.class, args);
    }
}
