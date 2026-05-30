package com.atlasflag.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * AtlasFlag Java SDK — evaluate feature flags and remote config with local caching,
 * graceful degradation, SSE-based live updates, and user attribute targeting.
 *
 * <pre>
 *   AtlasFlagClient client = new AtlasFlagClient.Builder()
 *       .baseUrl("https://your-backend.onrender.com")
 *       .environment("PRODUCTION")
 *       .cacheTtlSeconds(60)
 *       .build();
 *
 *   // Boolean flag
 *   boolean enabled = client.isEnabled("my-flag", "user-123", false);
 *
 *   // Remote config
 *   String color = client.getString("button-color", "user-123", "#0066CC");
 *   int timeout = client.getInt("api-timeout-ms", null, 5000);
 *
 *   // Attribute targeting
 *   Map&lt;String,String&gt; attrs = Map.of("plan", "pro", "country", "DE");
 *   boolean show = client.isEnabled("enterprise-dash", "user-42", attrs, false);
 *
 *   // Live updates via SSE
 *   client.onFlagChange("kill-switch", enabled -&gt; {
 *       if (!enabled) shutdownExperiment();
 *   });
 *
 *   client.shutdown();
 * </pre>
 *
 * The client never throws during evaluation — always returns {@code defaultValue} if the
 * service is unreachable and no cached value exists.
 */
public class AtlasFlagClient {

    private static final Logger logger = LoggerFactory.getLogger(AtlasFlagClient.class);

    private final String baseUrl;
    private final String environment;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Cache<String, Boolean> boolCache;
    private final Cache<String, String> stringCache;
    private final ScheduledExecutorService scheduler;
    private final long cacheTtlSeconds;
    private final boolean cacheEnabled;

    // SSE
    private volatile EventSource sseEventSource;
    private final Map<String, List<Consumer<Boolean>>> flagListeners = new ConcurrentHashMap<>();

    // Last-known-good values — used as stale fallback when the service is unreachable
    private final Map<String, Boolean> lastGoodBool   = new ConcurrentHashMap<>();
    private final Map<String, String>  lastGoodString = new ConcurrentHashMap<>();

    private AtlasFlagClient(Builder builder) {
        this.baseUrl = builder.baseUrl.replaceAll("/$", "");
        this.environment = builder.environment;
        this.cacheEnabled = builder.cacheEnabled;
        this.cacheTtlSeconds = builder.cacheTtlSeconds;

        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build();

        this.objectMapper = new ObjectMapper();

        if (cacheEnabled) {
            this.boolCache = Caffeine.newBuilder()
                .expireAfterWrite(cacheTtlSeconds, TimeUnit.SECONDS)
                .maximumSize(10_000)
                .build();
            this.stringCache = Caffeine.newBuilder()
                .expireAfterWrite(cacheTtlSeconds, TimeUnit.SECONDS)
                .maximumSize(10_000)
                .build();
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "atlasflag-cache-refresh");
                t.setDaemon(true);
                return t;
            });
            long refreshInterval = Math.max(1, (long) (cacheTtlSeconds * 0.8));
            scheduler.scheduleAtFixedRate(this::refreshCache,
                refreshInterval, refreshInterval, TimeUnit.SECONDS);
        } else {
            this.boolCache = null;
            this.stringCache = null;
            this.scheduler = null;
        }
    }

    /**
     * Returns a lightweight view of this client scoped to a different environment.
     * Shares the HTTP client — no new connections are opened. No background refresh.
     */
    public AtlasFlagClient withEnvironment(String environment) {
        return new AtlasFlagClient.Builder()
            .baseUrl(this.baseUrl)
            .environment(environment)
            .cacheEnabled(false)
            .build();
    }

    // ── Boolean evaluation ───────────────────────────────────────────────────

    /** Evaluate a flag without user context. Percentage rollouts and targeting are not applied. */
    public boolean isEnabled(String flagKey, boolean defaultValue) {
        return isEnabled(flagKey, null, null, defaultValue);
    }

    /** Evaluate a flag for a specific user. Percentage rollouts are deterministic. */
    public boolean isEnabled(String flagKey, String userId, boolean defaultValue) {
        return isEnabled(flagKey, userId, null, defaultValue);
    }

    /**
     * Evaluate a flag with user attributes for targeting-rule evaluation.
     *
     * @param attributes key-value pairs evaluated against the flag's targeting rules
     */
    public boolean isEnabled(String flagKey, String userId, Map<String, String> attributes, boolean defaultValue) {
        if (cacheEnabled && boolCache != null) {
            Boolean cached = boolCache.getIfPresent(flagKey);
            if (cached != null) return cached;
        }

        try {
            FlagEvaluationResponse res = fetchSingle(flagKey, userId, attributes);
            if (res != null) {
                boolean result = Boolean.TRUE.equals(res.getEnabled());
                if (cacheEnabled && boolCache != null) boolCache.put(flagKey, result);
                if (cacheEnabled && stringCache != null && res.getValue() != null)
                    stringCache.put(flagKey, res.getValue());
                lastGoodBool.put(flagKey, result);
                if (res.getValue() != null) lastGoodString.put(flagKey, res.getValue());
                return result;
            }
        } catch (Exception e) {
            logger.warn("Failed to evaluate flag '{}', using cache/default", flagKey, e);
        }

        // Stale fallback — prefer last known value over a hardcoded default
        Boolean stale = lastGoodBool.get(flagKey);
        if (stale != null) {
            logger.debug("Service unreachable; using last known value '{}' for flag '{}'", stale, flagKey);
            return stale;
        }

        logger.debug("Using default '{}' for flag '{}'", defaultValue, flagKey);
        return defaultValue;
    }

    // ── Remote config ────────────────────────────────────────────────────────

    /** Returns the string config value for the flag, or {@code defaultValue} if unavailable or disabled. */
    public String getString(String flagKey, String userId, String defaultValue) {
        return getString(flagKey, userId, null, defaultValue);
    }

    public String getString(String flagKey, String userId, Map<String, String> attributes, String defaultValue) {
        if (cacheEnabled && stringCache != null) {
            String cached = stringCache.getIfPresent(flagKey);
            if (cached != null) return cached;
        }

        try {
            FlagEvaluationResponse res = fetchSingle(flagKey, userId, attributes);
            if (res != null && res.getValue() != null) {
                if (cacheEnabled && stringCache != null) stringCache.put(flagKey, res.getValue());
                if (cacheEnabled && boolCache != null) boolCache.put(flagKey, Boolean.TRUE.equals(res.getEnabled()));
                lastGoodString.put(flagKey, res.getValue());
                lastGoodBool.put(flagKey, Boolean.TRUE.equals(res.getEnabled()));
                return res.getValue();
            }
        } catch (Exception e) {
            logger.warn("Failed to get string config '{}', using cache/default", flagKey, e);
        }

        // Stale fallback
        String stale = lastGoodString.get(flagKey);
        if (stale != null) {
            logger.debug("Service unreachable; using last known value for string flag '{}'", flagKey);
            return stale;
        }

        return defaultValue;
    }

    /** Returns the integer config value, or {@code defaultValue} if unavailable, disabled, or not a number. */
    public int getInt(String flagKey, String userId, int defaultValue) {
        String raw = getString(flagKey, userId, null);
        if (raw == null) return defaultValue;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    /** Returns the double config value, or {@code defaultValue} if unavailable, disabled, or not a number. */
    public double getDouble(String flagKey, String userId, double defaultValue) {
        String raw = getString(flagKey, userId, null);
        if (raw == null) return defaultValue;
        try { return Double.parseDouble(raw.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    /**
     * Deserializes the JSON config value into the given type.
     * Returns {@code defaultValue} if unavailable, disabled, or JSON parsing fails.
     */
    public <T> T getJson(String flagKey, String userId, T defaultValue, Class<T> type) {
        String raw = getString(flagKey, userId, null);
        if (raw == null) return defaultValue;
        try { return objectMapper.readValue(raw, type); }
        catch (Exception e) { return defaultValue; }
    }

    // ── Bulk evaluation ──────────────────────────────────────────────────────

    /**
     * Evaluate multiple flags in a single HTTP call.
     * Returns a map of flagKey → enabled. Missing flags use {@code defaultValue}.
     */
    public Map<String, Boolean> isEnabledBulk(List<String> flagKeys, String userId, boolean defaultValue) {
        return isEnabledBulk(flagKeys, userId, null, defaultValue);
    }

    public Map<String, Boolean> isEnabledBulk(List<String> flagKeys, String userId,
                                               Map<String, String> attributes, boolean defaultValue) {
        if (flagKeys == null || flagKeys.isEmpty()) return Map.of();

        Map<String, Boolean> results = new HashMap<>();
        List<String> toFetch = new ArrayList<>();

        for (String key : flagKeys) {
            if (cacheEnabled && boolCache != null) {
                Boolean cached = boolCache.getIfPresent(key);
                if (cached != null) { results.put(key, cached); continue; }
            }
            toFetch.add(key);
        }

        if (!toFetch.isEmpty()) {
            try {
                Map<String, FlagEvaluationResponse> fetched = fetchBulk(toFetch, userId, attributes);
                fetched.forEach((key, res) -> {
                    boolean enabled = Boolean.TRUE.equals(res.getEnabled());
                    results.put(key, enabled);
                    if (cacheEnabled && boolCache != null) boolCache.put(key, enabled);
                    if (cacheEnabled && stringCache != null && res.getValue() != null)
                        stringCache.put(key, res.getValue());
                });
            } catch (Exception e) {
                logger.warn("Bulk evaluation failed, using default for {} flags", toFetch.size(), e);
            }
        }

        flagKeys.forEach(key -> results.putIfAbsent(key, defaultValue));
        return results;
    }

    // ── SSE live updates ─────────────────────────────────────────────────────

    /**
     * Register a listener that is called whenever the flag's enabled state changes via SSE.
     * Automatically opens an SSE connection on first registration.
     *
     * <pre>
     *   client.onFlagChange("kill-switch", enabled -&gt; {
     *       if (!enabled) shutdownExperiment();
     *   });
     * </pre>
     */
    public void onFlagChange(String flagKey, Consumer<Boolean> listener) {
        flagListeners.computeIfAbsent(flagKey, k -> new CopyOnWriteArrayList<>()).add(listener);
        ensureSseConnected();
    }

    private synchronized void ensureSseConnected() {
        if (sseEventSource != null) return;

        Request request = new Request.Builder()
            .url(baseUrl + "/api/v1/flags/stream?environment=" + environment)
            .header("Accept", "text/event-stream")
            .build();

        sseEventSource = EventSources.createFactory(httpClient).newEventSource(request,
            new EventSourceListener() {
                @Override
                public void onEvent(EventSource source, String id, String type, String data) {
                    if (!"FLAG_CHANGED".equals(type)) return;
                    try {
                        JsonNode node = objectMapper.readTree(data);
                        String flagKey = node.path("flagKey").asText();
                        boolean enabled = node.path("enabled").asBoolean(false);
                        String value = node.has("value") && !node.get("value").isNull()
                            ? node.get("value").asText() : null;

                        if (cacheEnabled && boolCache != null) boolCache.put(flagKey, enabled);
                        if (cacheEnabled && stringCache != null && value != null)
                            stringCache.put(flagKey, value);
                        else if (cacheEnabled && stringCache != null)
                            stringCache.invalidate(flagKey);
                        lastGoodBool.put(flagKey, enabled);
                        if (value != null) lastGoodString.put(flagKey, value);

                        List<Consumer<Boolean>> listeners =
                            flagListeners.getOrDefault(flagKey, List.of());
                        listeners.forEach(l -> {
                            try { l.accept(enabled); }
                            catch (Exception e) { logger.warn("Flag listener threw for '{}'", flagKey, e); }
                        });
                    } catch (Exception e) {
                        logger.warn("Failed to process SSE event", e);
                    }
                }

                @Override
                public void onFailure(EventSource source, Throwable t, Response response) {
                    logger.warn("SSE connection lost — scheduling reconnect in 30s", t);
                    synchronized (AtlasFlagClient.this) { sseEventSource = null; }
                    scheduleReconnect();
                }
            });
    }

    private void scheduleReconnect() {
        if (flagListeners.isEmpty()) return;
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.schedule(this::ensureSseConnected, 30, TimeUnit.SECONDS);
        } else {
            Thread t = new Thread(() -> {
                try { Thread.sleep(30_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                if (!flagListeners.isEmpty()) ensureSseConnected();
            }, "atlasflag-sse-reconnect");
            t.setDaemon(true);
            t.start();
        }
    }

    // ── Internal HTTP helpers ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private FlagEvaluationResponse fetchSingle(String flagKey, String userId,
                                               Map<String, String> attributes) throws IOException {
        FlagEvaluationRequest req = new FlagEvaluationRequest(flagKey, environment, userId, attributes);
        String json = objectMapper.writeValueAsString(req);

        Request request = new Request.Builder()
            .url(baseUrl + "/api/v1/flags/evaluate")
            .post(RequestBody.create(json, MediaType.get("application/json; charset=utf-8")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                // Use raw Map parsing (consistent with fetchBulk) to avoid Jackson reflection
                // issues with private static inner classes in Java 17+ environments.
                Map<String, Object> raw = objectMapper.readValue(response.body().string(), Map.class);
                FlagEvaluationResponse res = new FlagEvaluationResponse();
                Object enabled = raw.get("enabled");
                Object value   = raw.get("value");
                if (enabled instanceof Boolean) res.setEnabled((Boolean) enabled);
                if (value   instanceof String)  res.setValue((String) value);
                return res;
            }
            logger.warn("Evaluate returned HTTP {} for '{}'", response.code(), flagKey);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, FlagEvaluationResponse> fetchBulk(List<String> flagKeys, String userId,
                                                           Map<String, String> attributes) throws IOException {
        BulkEvaluationRequest req = new BulkEvaluationRequest(flagKeys, environment, userId, attributes);
        String json = objectMapper.writeValueAsString(req);

        Request request = new Request.Builder()
            .url(baseUrl + "/api/v1/flags/evaluate/bulk")
            .post(RequestBody.create(json, MediaType.get("application/json; charset=utf-8")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                Map<String, Map<String, Object>> raw = objectMapper.readValue(
                    response.body().string(), Map.class);
                Map<String, FlagEvaluationResponse> results = new HashMap<>();
                raw.forEach((key, val) -> {
                    FlagEvaluationResponse res = new FlagEvaluationResponse();
                    Object enabled = val.get("enabled");
                    Object value = val.get("value");
                    if (enabled instanceof Boolean) res.setEnabled((Boolean) enabled);
                    if (value instanceof String) res.setValue((String) value);
                    results.put(key, res);
                });
                return results;
            }
            logger.warn("Bulk evaluate returned HTTP {}", response.code());
            return Map.of();
        }
    }

    private void refreshCache() {
        if (boolCache == null) return;
        Set<String> keys = boolCache.asMap().keySet();
        if (keys.isEmpty()) return;

        logger.debug("Refreshing {} cached flags", keys.size());
        try {
            Map<String, FlagEvaluationResponse> fresh = fetchBulk(new ArrayList<>(keys), null, null);
            fresh.forEach((key, res) -> {
                boolean enabled = Boolean.TRUE.equals(res.getEnabled());
                boolCache.put(key, enabled);
                if (stringCache != null && res.getValue() != null)
                    stringCache.put(key, res.getValue());
                lastGoodBool.put(key, enabled);
                if (res.getValue() != null) lastGoodString.put(key, res.getValue());
            });
        } catch (Exception e) {
            logger.warn("Cache refresh failed, keeping stale values", e);
        }
    }

    /** Shut down background threads, SSE connection, and HTTP connections. Call when your app stops. */
    public void shutdown() {
        if (sseEventSource != null) {
            sseEventSource.cancel();
            sseEventSource = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) scheduler.shutdownNow();
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    // ── Inner DTOs ───────────────────────────────────────────────────────────

    private static class FlagEvaluationRequest {
        private final String flagKey;
        private final String environment;
        private final String userId;
        private final Map<String, String> attributes;

        FlagEvaluationRequest(String flagKey, String environment, String userId,
                              Map<String, String> attributes) {
            this.flagKey = flagKey;
            this.environment = environment;
            this.userId = userId;
            this.attributes = attributes;
        }

        public String getFlagKey()                { return flagKey; }
        public String getEnvironment()            { return environment; }
        public String getUserId()                 { return userId; }
        public Map<String, String> getAttributes(){ return attributes; }
    }

    private static class BulkEvaluationRequest {
        private final List<String> flagKeys;
        private final String environment;
        private final String userId;
        private final Map<String, String> attributes;

        BulkEvaluationRequest(List<String> flagKeys, String environment, String userId,
                              Map<String, String> attributes) {
            this.flagKeys = flagKeys;
            this.environment = environment;
            this.userId = userId;
            this.attributes = attributes;
        }

        public List<String> getFlagKeys()         { return flagKeys; }
        public String getEnvironment()            { return environment; }
        public String getUserId()                 { return userId; }
        public Map<String, String> getAttributes(){ return attributes; }
    }

    private static class FlagEvaluationResponse {
        private Boolean enabled;
        private String reason;
        private String value;

        public Boolean getEnabled()       { return enabled; }
        public void setEnabled(Boolean v) { this.enabled = v; }
        public String getReason()         { return reason; }
        public void setReason(String r)   { this.reason = r; }
        public String getValue()          { return value; }
        public void setValue(String v)    { this.value = v; }
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    public static class Builder {
        private String baseUrl = "http://localhost:8080";
        private String environment = "DEVELOPMENT";
        private boolean cacheEnabled = true;
        private long cacheTtlSeconds = 60;

        /** Base URL of the AtlasFlag backend, e.g. {@code https://myapp.onrender.com} */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /** Environment to evaluate flags in: DEVELOPMENT, STAGING, or PRODUCTION */
        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        /** Enable or disable local caching (default: true) */
        public Builder cacheEnabled(boolean cacheEnabled) {
            this.cacheEnabled = cacheEnabled;
            return this;
        }

        /**
         * How long evaluated flag values are cached locally (seconds).
         * Background refresh runs at 80% of this interval to keep the cache warm.
         * Default: 60 seconds.
         */
        public Builder cacheTtlSeconds(long seconds) {
            this.cacheTtlSeconds = seconds;
            return this;
        }

        public AtlasFlagClient build() {
            if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl is required");
            if (environment == null || environment.isBlank()) throw new IllegalArgumentException("environment is required");
            return new AtlasFlagClient(this);
        }
    }
}
