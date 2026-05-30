package com.atlasflag.starter;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.*;

/**
 * Registers a Spring bean only when the specified feature flag is in the given state.
 *
 * <p>The flag is evaluated at application-context startup by making a direct HTTP call
 * to the AtlasFlag service. If the service is unreachable, {@code defaultValue} is used.
 *
 * <pre>
 * // Bean is only created when "experimental-cache" is enabled
 * {@literal @}Bean
 * {@literal @}ConditionalOnFeatureFlag("experimental-cache")
 * public CacheManager experimentalCache() { ... }
 *
 * // Bean is only created when "legacy-api" is DISABLED
 * {@literal @}Bean
 * {@literal @}ConditionalOnFeatureFlag(value = "legacy-api", match = false)
 * public ApiRouter modernRouter() { ... }
 * </pre>
 *
 * <p><b>Startup impact:</b> Each {@code @ConditionalOnFeatureFlag} annotation causes
 * one HTTP call during context refresh. Keep the timeout short ({@link #timeoutSeconds})
 * and ensure the flag service is reachable before your application starts.
 *
 * <p><b>Note:</b> Does not require spring-boot-starter-aop.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnFeatureFlagCondition.class)
public @interface ConditionalOnFeatureFlag {

    /** The flag key to evaluate. */
    String value();

    /**
     * The expected flag state for the condition to match.
     * {@code true} (default) = condition matches when the flag is enabled.
     * {@code false} = condition matches when the flag is disabled.
     */
    boolean match() default true;

    /** Default value used if the flag service is unreachable at startup. Default: false. */
    boolean defaultValue() default false;

    /** HTTP call timeout (seconds) during context refresh. Default: 2. */
    int timeoutSeconds() default 2;
}
