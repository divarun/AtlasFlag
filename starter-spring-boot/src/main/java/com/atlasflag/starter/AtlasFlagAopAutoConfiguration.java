package com.atlasflag.starter;

import com.atlasflag.sdk.AtlasFlagClient;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configures the {@link FeatureFlagAspect} when AspectJ is on the classpath.
 *
 * <p>To enable {@literal @FeatureFlag} method interception, add to your project:
 * <pre>
 * // build.gradle
 * implementation 'org.springframework.boot:spring-boot-starter-aop'
 * </pre>
 *
 * <p>The aspect is only created when:
 * <ul>
 *   <li>AspectJ ({@code ProceedingJoinPoint}) is on the classpath
 *   <li>An {@link AtlasFlagClient} bean exists
 *   <li>{@code atlasflag.base-url} is set
 * </ul>
 */
@AutoConfiguration
@AutoConfigureAfter(AtlasFlagAutoConfiguration.class)
@ConditionalOnClass({ProceedingJoinPoint.class, AtlasFlagClient.class})
@ConditionalOnBean(AtlasFlagClient.class)
@ConditionalOnProperty(prefix = "atlasflag", name = "base-url")
@EnableConfigurationProperties(AtlasFlagProperties.class)
public class AtlasFlagAopAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FeatureFlagAspect featureFlagAspect(AtlasFlagClient client,
                                               UserIdProvider userIdProvider,
                                               AtlasFlagProperties props) {
        return new FeatureFlagAspect(client, userIdProvider, props.getEnvironment());
    }
}
