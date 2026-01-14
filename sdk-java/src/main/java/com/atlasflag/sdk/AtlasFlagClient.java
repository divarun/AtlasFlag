package com.atlasflag.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AtlasFlag Java SDK Client
 * 
 * Provides resilient feature flag evaluation with:
 * - Caching for performance
 * - Graceful degradation on service unavailability
 * - Non-blocking evaluation
 */
public class AtlasFlagClient {
    
    private static final Logger logger = LoggerFactory.getLogger(AtlasFlagClient.class);
    
    private final String baseUrl;
    private final String environment;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Cache<String, CachedFlag> cache;
    private final ScheduledExecutorService scheduler;
    private final long cacheRefreshIntervalSeconds;
    private final boolean cacheEnabled;
    
    private AtlasFlagClient(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.environment = builder.environment;
        this.cacheEnabled = builder.cacheEnabled;
        this.cacheRefreshIntervalSeconds = builder.cacheRefreshIntervalSeconds;
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build();
        
        this.objectMapper = new ObjectMapper();
        
        // Use Caffeine cache with TTL and size limits to prevent memory leaks
        if (cacheEnabled) {
            this.cache = Caffeine.newBuilder()
                .expireAfterWrite(cacheRefreshIntervalSeconds, TimeUnit.SECONDS)
                .maximumSize(10000) // Limit cache size
                .build();
        } else {
            this.cache = null;
        }
        
        this.scheduler = Executors.newScheduledThreadPool(1);
        
        if (cacheEnabled) {
            startCacheRefresh();
        }
    }
    
    /**
     * Evaluate a feature flag
     * 
     * @param flagKey The flag key to evaluate
     * @param defaultValue Default value if flag cannot be evaluated
     * @return true if flag is enabled, false otherwise
     */
    public boolean isEnabled(String flagKey, boolean defaultValue) {
        return isEnabled(flagKey, null, defaultValue);
    }
    
    /**
     * Evaluate a feature flag with user context
     * 
     * @param flagKey The flag key to evaluate
     * @param userId User ID for percentage-based rollouts
     * @param defaultValue Default value if flag cannot be evaluated
     * @return true if flag is enabled, false otherwise
     */
    public boolean isEnabled(String flagKey, String userId, boolean defaultValue) {
        // Check cache first
        if (cacheEnabled && cache != null) {
            CachedFlag cached = cache.getIfPresent(flagKey);
            if (cached != null && !cached.isExpired()) {
                return cached.isEnabled();
            }
        }
        
        // Try to fetch from service
        try {
            FlagEvaluationResponse response = evaluateFlagFromService(flagKey, userId);
            if (response != null && response.getEnabled() != null) {
                if (cacheEnabled && cache != null) {
                    cache.put(flagKey, new CachedFlag(response.getEnabled(), 
                        System.currentTimeMillis() + (cacheRefreshIntervalSeconds * 1000)));
                }
                return response.getEnabled();
            }
        } catch (Exception e) {
            logger.warn("Failed to evaluate flag '{}' from service, using cached/default value", flagKey, e);
        }
        
        // Fallback to cached value if available
        if (cacheEnabled && cache != null) {
            CachedFlag cached = cache.getIfPresent(flagKey);
            if (cached != null) {
                logger.debug("Using cached value for flag '{}'", flagKey);
                return cached.isEnabled();
            }
        }
        
        // Final fallback to default
        logger.debug("Using default value '{}' for flag '{}'", defaultValue, flagKey);
        return defaultValue;
    }
    
    private FlagEvaluationResponse evaluateFlagFromService(String flagKey, String userId) throws IOException {
        FlagEvaluationRequest request = new FlagEvaluationRequest();
        request.setFlagKey(flagKey);
        request.setEnvironment(environment);
        request.setUserId(userId);
        
        String json = objectMapper.writeValueAsString(request);
        RequestBody body = RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
        
        Request httpRequest = new Request.Builder()
            .url(baseUrl + "/api/v1/flags/evaluate")
            .post(body)
            .build();
        
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String responseBody = response.body().string();
                return objectMapper.readValue(responseBody, FlagEvaluationResponse.class);
            } else {
                logger.warn("Failed to evaluate flag: HTTP {}", response.code());
                return null;
            }
        }
    }
    
    private void startCacheRefresh() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                refreshCache();
            } catch (Exception e) {
                logger.error("Error refreshing cache", e);
            }
        }, cacheRefreshIntervalSeconds, cacheRefreshIntervalSeconds, TimeUnit.SECONDS);
    }
    
    private void refreshCache() {
        // In a full implementation, this would fetch all flags for the environment
        // For MVP, we'll refresh on-demand
        logger.debug("Cache refresh scheduled");
    }
    
    /**
     * Shutdown the client and cleanup resources
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    // Inner classes
    private static class CachedFlag {
        private final boolean enabled;
        private final long expiresAt;
        
        CachedFlag(boolean enabled, long expiresAt) {
            this.enabled = enabled;
            this.expiresAt = expiresAt;
        }
        
        boolean isEnabled() {
            return enabled;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
    
    private static class FlagEvaluationRequest {
        private String flagKey;
        private String environment;
        private String userId;
        
        public String getFlagKey() { return flagKey; }
        public void setFlagKey(String flagKey) { this.flagKey = flagKey; }
        public String getEnvironment() { return environment; }
        public void setEnvironment(String environment) { this.environment = environment; }
        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
    }
    
    private static class FlagEvaluationResponse {
        private String flagKey;
        private Boolean enabled;
        private String reason;
        
        public String getFlagKey() { return flagKey; }
        public void setFlagKey(String flagKey) { this.flagKey = flagKey; }
        public Boolean getEnabled() { return enabled; }
        public void setEnabled(Boolean enabled) { this.enabled = enabled; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
    
    // Builder
    public static class Builder {
        private String baseUrl = "http://localhost:8080";
        private String environment = "default";
        private boolean cacheEnabled = true;
        private long cacheRefreshIntervalSeconds = 60;
        
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }
        
        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }
        
        public Builder cacheEnabled(boolean cacheEnabled) {
            this.cacheEnabled = cacheEnabled;
            return this;
        }
        
        public Builder cacheRefreshIntervalSeconds(long seconds) {
            this.cacheRefreshIntervalSeconds = seconds;
            return this;
        }
        
        public AtlasFlagClient build() {
            return new AtlasFlagClient(this);
        }
    }
}
