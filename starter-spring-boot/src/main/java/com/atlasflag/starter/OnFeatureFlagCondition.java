package com.atlasflag.starter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Condition that evaluates a feature flag by making a direct HTTP call
 * to the AtlasFlag service during application-context refresh.
 */
public class OnFeatureFlagCondition implements Condition {

    private static final Logger logger = LoggerFactory.getLogger(OnFeatureFlagCondition.class);

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(ConditionalOnFeatureFlag.class.getName());
        if (attrs == null) return true;

        String flagKey    = (String)  attrs.get("value");
        boolean match     = (boolean) attrs.get("match");
        boolean fallback  = (boolean) attrs.get("defaultValue");
        int timeoutSecs   = (int)     attrs.get("timeoutSeconds");

        String baseUrl = context.getEnvironment().getProperty("atlasflag.base-url");
        String env     = context.getEnvironment().getProperty("atlasflag.environment", "DEVELOPMENT");

        if (baseUrl == null || baseUrl.isBlank()) {
            logger.debug("atlasflag.base-url not set; @ConditionalOnFeatureFlag('{}') uses defaultValue={}", flagKey, fallback);
            return match == fallback;
        }

        boolean flagEnabled = fetchFlag(baseUrl.replaceAll("/$", ""), flagKey, env, timeoutSecs, fallback);
        boolean conditionMet = (match == flagEnabled);
        logger.debug("@ConditionalOnFeatureFlag('{}') → flag={}, match={}, conditionMet={}", flagKey, flagEnabled, match, conditionMet);
        return conditionMet;
    }

    private boolean fetchFlag(String baseUrl, String flagKey, String environment,
                               int timeoutSeconds, boolean fallback) {
        try {
            String payload = String.format(
                "{\"flagKey\":\"%s\",\"environment\":\"%s\"}",
                flagKey.replace("\"", "\\\""),
                environment.replace("\"", "\\\"")
            );

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/flags/evaluate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();

            HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body() != null) {
                // Match "enabled" : true at the JSON field level, not as a substring anywhere.
                // The pattern anchors to the field name and handles optional whitespace.
                return Pattern.compile("\"enabled\"\\s*:\\s*true\\b")
                              .matcher(response.body()).find();
            }
            logger.warn("@ConditionalOnFeatureFlag: server returned HTTP {} for '{}', using defaultValue={}",
                response.statusCode(), flagKey, fallback);
        } catch (Exception e) {
            logger.warn("@ConditionalOnFeatureFlag: could not reach AtlasFlag for '{}', using defaultValue={}: {}",
                flagKey, fallback, e.getMessage());
        }
        return fallback;
    }
}
