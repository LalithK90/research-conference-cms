package com.icosiam.cms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan(basePackages = "com.icosiam.cms")
@ComponentScan(basePackages = "com.icosiam.cms",
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.icosiam\\.cms\\.(core|proceedings|web\\.config)\\..*"))
@EnableJpaAuditing
@EnableJpaRepositories(basePackages = "com.icosiam.cms")
public class ConferenceCmsApplication {

    public static void main(String[] args) {
        org.springframework.boot.SpringApplication app = new org.springframework.boot.SpringApplication(ConferenceCmsApplication.class);
        app.setAllowBeanDefinitionOverriding(true);
        app.run(args);
    }
}
