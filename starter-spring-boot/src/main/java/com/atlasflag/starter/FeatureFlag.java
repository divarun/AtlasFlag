package com.atlasflag.starter;

import java.lang.annotation.*;

/**
 * Gates a method behind a feature flag. If the flag is disabled, the method is
 * skipped and a type-safe zero-value is returned.
 *
 * <p>Requires {@code spring-boot-starter-aop} on the classpath.
 *
 * <pre>
 * {@literal @}Service
 * public class CheckoutService {
 *
 *     // Skipped entirely if "new-checkout" is disabled
 *     {@literal @}FeatureFlag("new-checkout")
 *     public CheckoutResult runNewFlow(Order order) { ... }
 *
 *     // Custom default and environment override
 *     {@literal @}FeatureFlag(value = "beta-pricing", defaultValue = true, environment = "STAGING")
 *     public PricingResult computePrice(String userId) { ... }
 * }
 * </pre>
 *
 * <p><b>Return values when the flag is disabled:</b>
 * <ul>
 *   <li>{@code void} — method body is skipped silently
 *   <li>{@code boolean} — returns {@code false}
 *   <li>{@code int / long / double} (and boxed) — returns {@code 0}
 *   <li>{@code Optional} — returns {@code Optional.empty()}
 *   <li>{@code List / Set / Map} — returns an empty immutable collection
 *   <li>Any other object — returns {@code null}
 * </ul>
 *
 * <p><b>UserId resolution:</b> The current user ID is resolved via the
 * {@link UserIdProvider} bean. By default this reads from Spring Security's
 * {@code SecurityContext}. Register your own {@code UserIdProvider} bean to override.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FeatureFlag {

    /** The flag key to evaluate. */
    String value();

    /**
     * Default value used when the flag service is unreachable.
     * Does NOT control what happens when the flag is explicitly disabled
     * in the service — that always returns the zero-value for the return type.
     */
    boolean defaultValue() default false;

    /**
     * Override the environment for this specific flag.
     * If empty (default), uses the value from {@code atlasflag.environment}.
     */
    String environment() default "";
}
