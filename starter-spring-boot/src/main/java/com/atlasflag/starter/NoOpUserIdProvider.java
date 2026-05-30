package com.atlasflag.starter;

/**
 * Fallback UserIdProvider used when Spring Security is not on the classpath.
 * Always returns null, which disables rollout-percentage evaluation server-side.
 */
public class NoOpUserIdProvider implements UserIdProvider {
    @Override
    public String getCurrentUserId() {
        return null;
    }
}
