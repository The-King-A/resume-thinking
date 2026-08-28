package com.resumethinking.platform.dev;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(prefix = "app.demo-seed", name = "enabled", havingValue = "true")
class DemoDataInitializer {
    @Bean
    ApplicationRunner demoDataSeedRunner(DemoDataSeedService seedService,
                                         @Value("${app.demo-seed.user-password:}") String userPassword,
                                         @Value("${app.demo-seed.admin-password:}") String adminPassword) {
        return arguments -> {
            validatePassword(userPassword);
            validatePassword(adminPassword);
            seedService.seed(new DemoDataSeedService.DemoCredentials(userPassword, adminPassword));
        };
    }

    private static void validatePassword(String password) {
        if (password == null || password.trim().length() < 12) {
            throw new IllegalStateException("demo seed password must be at least 12 characters");
        }
    }
}
