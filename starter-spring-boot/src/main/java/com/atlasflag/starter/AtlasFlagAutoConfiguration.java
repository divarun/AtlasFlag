package com.atlasflag.starter;

import com.atlasflag.sdk.AtlasFlagClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures an {@link AtlasFlagClient} bean from {@code application.properties}.
 *
 * <p>Activated when:
 * <ul>
 *   <li>{@code atlas-flag-sdk-java} is on the classpath
 *   <li>{@code atlasflag.base-url} property is set
 * </ul>
 *
 * <p>Override any bean by declaring your own:
 * <pre>
 * {@literal @}Bean
 * public AtlasFlagClient atlasFlagClient() {
 *     return new AtlasFlagClient.Builder()
 *         .baseUrl("https://my-backend.onrender.com")
 *         .environment("PRODUCTION")
 *         .build();
 * }
 * </pre>
 */
@AutoConfiguration
@ConditionalOnClass(AtlasFlagClient.class)
@ConditionalOnProperty(prefix = "atlasflag", name = "base-url")
@EnableConfigurationProperties(AtlasFlagProperties.class)
public class AtlasFlagAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AtlasFlagAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public AtlasFlagClient atlasFlagClient(AtlasFlagProperties props) {
        AtlasFlagClient client = new AtlasFlagClient.Builder()
            .baseUrl(props.getBaseUrl())
            .environment(props.getEnvironment())
            .cacheEnabled(props.getCache().isEnabled())
            .cacheTtlSeconds(props.getCache().getTtlSeconds())
            .build();

        logger.info("AtlasFlagClient configured — baseUrl={}, environment={}, cache={}s",
            props.getBaseUrl(), props.getEnvironment(),
            props.getCache().isEnabled() ? props.getCache().getTtlSeconds() + "s TTL" : "disabled");
        return client;
    }

    // ── UserIdProvider beans ─────────────────────────────────────────────────

    /** Uses Spring Security's SecurityContext when available. */
    @Bean
    @ConditionalOnMissingBean(UserIdProvider.class)
    @ConditionalOnClass(name = "org.springframework.security.core.context.SecurityContextHolder")
    public UserIdProvider securityContextUserIdProvider() {
        return new SecurityContextUserIdProvider();
    }

    /** No-op fallback when Spring Security is not on the classpath. */
    @Bean
    @ConditionalOnMissingBean(UserIdProvider.class)
    @ConditionalOnMissingClass("org.springframework.security.core.context.SecurityContextHolder")
    public UserIdProvider noOpUserIdProvider() {
        return new NoOpUserIdProvider();
    }
}
