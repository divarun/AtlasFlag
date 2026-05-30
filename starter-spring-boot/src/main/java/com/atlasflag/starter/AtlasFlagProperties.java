package com.atlasflag.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the AtlasFlag Spring Boot starter.
 *
 * <pre>
 * # application.properties
 * atlasflag.base-url=https://your-backend.onrender.com
 * atlasflag.environment=PRODUCTION
 * atlasflag.cache.ttl-seconds=60
 * </pre>
 */
@ConfigurationProperties(prefix = "atlasflag")
public class AtlasFlagProperties {

    /** Base URL of the AtlasFlag backend. Required. */
    private String baseUrl;

    /** Environment to evaluate flags against. Default: DEVELOPMENT */
    private String environment = "DEVELOPMENT";

    private final Cache cache = new Cache();
    private final Http http = new Http();

    public String getBaseUrl()               { return baseUrl; }
    public void setBaseUrl(String baseUrl)   { this.baseUrl = baseUrl; }
    public String getEnvironment()           { return environment; }
    public void setEnvironment(String env)   { this.environment = env; }
    public Cache getCache()                  { return cache; }
    public Http getHttp()                    { return http; }

    public static class Cache {
        /** Enable local Caffeine cache for evaluated flag results. Default: true */
        private boolean enabled = true;
        /** How long evaluated results are cached (seconds). Default: 60 */
        private long ttlSeconds = 60;

        public boolean isEnabled()               { return enabled; }
        public void setEnabled(boolean enabled)  { this.enabled = enabled; }
        public long getTtlSeconds()              { return ttlSeconds; }
        public void setTtlSeconds(long ttl)      { this.ttlSeconds = ttl; }
    }

    public static class Http {
        /** HTTP connect timeout (seconds). Default: 2 */
        private int connectTimeoutSeconds = 2;
        /** HTTP read timeout (seconds). Default: 3 */
        private int readTimeoutSeconds = 3;

        public int getConnectTimeoutSeconds()              { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int seconds)  { this.connectTimeoutSeconds = seconds; }
        public int getReadTimeoutSeconds()                 { return readTimeoutSeconds; }
        public void setReadTimeoutSeconds(int seconds)     { this.readTimeoutSeconds = seconds; }
    }
}
