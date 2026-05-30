package com.atlasflag.starter;

import com.atlasflag.sdk.AtlasFlagClient;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * AOP aspect that intercepts {@link FeatureFlag}-annotated methods.
 *
 * <p>Requires {@code spring-boot-starter-aop} on the classpath.
 *
 * <p>When a flag is disabled, the method body is skipped and a zero-value
 * appropriate for the return type is returned automatically.
 */
@Aspect
public class FeatureFlagAspect {

    private static final Logger logger = LoggerFactory.getLogger(FeatureFlagAspect.class);

    private final AtlasFlagClient client;
    private final UserIdProvider userIdProvider;
    private final String defaultEnvironment;

    public FeatureFlagAspect(AtlasFlagClient client, UserIdProvider userIdProvider,
                              String defaultEnvironment) {
        this.client = client;
        this.userIdProvider = userIdProvider;
        this.defaultEnvironment = defaultEnvironment;
    }

    @Around("@annotation(featureFlag)")
    public Object around(ProceedingJoinPoint pjp, FeatureFlag featureFlag) throws Throwable {
        String flagKey     = featureFlag.value();
        boolean fallback   = featureFlag.defaultValue();
        String environment = featureFlag.environment().isBlank()
            ? defaultEnvironment
            : featureFlag.environment();

        String userId = resolveUserId();

        // Build a client scoped to the right environment, or reuse the default
        boolean enabled = evaluateFlag(flagKey, userId, fallback, environment);

        if (enabled) {
            return pjp.proceed();
        }

        Class<?> returnType = ((MethodSignature) pjp.getSignature()).getReturnType();
        Object zero = zeroValueFor(returnType);
        logger.debug("@FeatureFlag('{}') disabled → skipping method, returning {}", flagKey, zero);
        return zero;
    }

    private boolean evaluateFlag(String flagKey, String userId, boolean fallback, String environment) {
        try {
            // If the environment matches the client's configured environment, use it directly
            if (environment.equals(defaultEnvironment)) {
                return client.isEnabled(flagKey, userId, fallback);
            }
            // For environment overrides, build a temporary client scoped to that environment
            AtlasFlagClient scoped = client.withEnvironment(environment);
            return scoped.isEnabled(flagKey, userId, fallback);
        } catch (Exception e) {
            logger.warn("@FeatureFlag('{}') evaluation failed, using fallback={}", flagKey, fallback, e);
            return fallback;
        }
    }

    private String resolveUserId() {
        try {
            return userIdProvider.getCurrentUserId();
        } catch (Exception e) {
            logger.debug("Could not resolve userId from UserIdProvider", e);
            return null;
        }
    }

    private Object zeroValueFor(Class<?> type) {
        if (type == void.class || type == Void.class)               return null;
        if (type == boolean.class || type == Boolean.class)         return Boolean.FALSE;
        if (type == byte.class    || type == Byte.class)            return (byte) 0;
        if (type == short.class   || type == Short.class)           return (short) 0;
        if (type == int.class     || type == Integer.class)         return 0;
        if (type == long.class    || type == Long.class)            return 0L;
        if (type == float.class   || type == Float.class)           return 0f;
        if (type == double.class  || type == Double.class)          return 0d;
        if (type == char.class    || type == Character.class)       return '\0';
        if (Optional.class.isAssignableFrom(type))                  return Optional.empty();
        if (List.class.isAssignableFrom(type))                      return List.of();
        if (Set.class.isAssignableFrom(type))                       return Set.of();
        if (Map.class.isAssignableFrom(type))                       return Map.of();
        return null;
    }
}
