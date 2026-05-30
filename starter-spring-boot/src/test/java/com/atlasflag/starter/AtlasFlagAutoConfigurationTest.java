package com.atlasflag.starter;

import com.atlasflag.sdk.AtlasFlagClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AtlasFlagAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AtlasFlagAutoConfiguration.class));

    @Test
    void noClientWhenBaseUrlMissing() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(AtlasFlagClient.class));
    }

    @Test
    void clientCreatedWhenBaseUrlPresent() {
        runner.withPropertyValues("atlasflag.base-url=http://localhost:8080")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(AtlasFlagClient.class);
                assertThat(ctx).hasSingleBean(UserIdProvider.class);
            });
    }

    @Test
    void customEnvironmentProperty() {
        runner.withPropertyValues(
                "atlasflag.base-url=http://localhost:8080",
                "atlasflag.environment=STAGING")
            .run(ctx -> assertThat(ctx).hasSingleBean(AtlasFlagClient.class));
    }

    @Test
    void cacheTtlProperty() {
        runner.withPropertyValues(
                "atlasflag.base-url=http://localhost:8080",
                "atlasflag.cache.ttl-seconds=120")
            .run(ctx -> {
                AtlasFlagProperties props = ctx.getBean(AtlasFlagProperties.class);
                assertThat(props.getCache().getTtlSeconds()).isEqualTo(120);
            });
    }

    @Test
    void userDefinedClientBeanTakesPrecedence() {
        runner.withPropertyValues("atlasflag.base-url=http://localhost:8080")
            .withBean(AtlasFlagClient.class, () ->
                new AtlasFlagClient.Builder()
                    .baseUrl("http://custom-host:9090")
                    .environment("PRODUCTION")
                    .build())
            .run(ctx -> assertThat(ctx).hasSingleBean(AtlasFlagClient.class));
    }

    @Test
    void cachDisabledProperty() {
        runner.withPropertyValues(
                "atlasflag.base-url=http://localhost:8080",
                "atlasflag.cache.enabled=false")
            .run(ctx -> {
                AtlasFlagProperties props = ctx.getBean(AtlasFlagProperties.class);
                assertThat(props.getCache().isEnabled()).isFalse();
            });
    }
}
