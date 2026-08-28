package com.resumethinking.platform.dev;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DemoDataInitializerTest {
    private static final ApplicationArguments NO_ARGUMENTS = new DefaultApplicationArguments();

    @Test
    void rejectsMissingBlankOrShortPasswordsBeforeSeeding() {
        assertInvalidCredentials("", "long-enough-admin", null);
        assertInvalidCredentials("   ", "long-enough-admin", null);
        assertInvalidCredentials("short", "long-enough-admin", "short");
        assertInvalidCredentials("long-enough-user", "", null);
        assertInvalidCredentials("long-enough-user", "   ", null);
        assertInvalidCredentials("long-enough-user", "short", "short");
    }

    @Test
    void seedsOnceWithTheConfiguredPasswords() {
        localContext("app.demo-seed.enabled=true", "app.demo-seed.user-password=user-password-123",
                "app.demo-seed.admin-password=admin-password-123")
                .run(context -> {
                    ApplicationRunner runner = context.getBean(ApplicationRunner.class);

                    run(runner);

                    ArgumentCaptor<DemoDataSeedService.DemoCredentials> credentials =
                            ArgumentCaptor.forClass(DemoDataSeedService.DemoCredentials.class);
                    verify(context.getBean(DemoDataSeedService.class)).seed(credentials.capture());
                    assertThat(credentials.getValue().userPassword()).isEqualTo("user-password-123");
                    assertThat(credentials.getValue().adminPassword()).isEqualTo("admin-password-123");
                });
    }

    @Test
    void runnerIsOnlyAvailableForAnExplicitlyEnabledLocalProfile() {
        baseContext("app.demo-seed.enabled=true").run(context ->
                assertThat(context.getBeansOfType(ApplicationRunner.class)).isEmpty());
        localContext().run(context -> assertThat(context.getBeansOfType(ApplicationRunner.class)).isEmpty());
        localContext("app.demo-seed.enabled=false").run(context ->
                assertThat(context.getBeansOfType(ApplicationRunner.class)).isEmpty());
        localContext("app.demo-seed.enabled=true").run(context ->
                assertThat(context.getBeansOfType(ApplicationRunner.class)).hasSize(1));
    }

    private void assertInvalidCredentials(String userPassword, String adminPassword, String leakedValue) {
        localContext("app.demo-seed.enabled=true", "app.demo-seed.user-password=" + userPassword,
                "app.demo-seed.admin-password=" + adminPassword)
                .run(context -> {
                    ApplicationRunner runner = context.getBean(ApplicationRunner.class);

                    Throwable failure = catchThrowable(() -> run(runner));
                    assertThat(failure).isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("demo seed password");
                    if (leakedValue != null) {
                        assertThat(failure).hasMessageNotContaining(leakedValue);
                    }
                    verify(context.getBean(DemoDataSeedService.class), never()).seed(org.mockito.ArgumentMatchers.any());
                });
    }

    private ApplicationContextRunner baseContext(String... properties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(DemoDataInitializer.class, TestConfiguration.class)
                .withPropertyValues(properties);
    }

    private ApplicationContextRunner localContext(String... properties) {
        return baseContext(properties)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"));
    }

    private void run(ApplicationRunner runner) throws Exception {
        runner.run(NO_ARGUMENTS);
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {
        @Bean
        DemoDataSeedService demoDataSeedService() {
            return mock(DemoDataSeedService.class);
        }
    }
}
