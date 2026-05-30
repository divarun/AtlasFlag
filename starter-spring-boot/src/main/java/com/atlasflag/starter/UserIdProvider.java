package com.atlasflag.starter;

/**
 * Provides the current user's ID for percentage-based flag rollouts.
 *
 * <p>A default implementation is registered automatically:
 * <ul>
 *   <li>If Spring Security is on the classpath, the authenticated principal name is used.
 *   <li>Otherwise, {@code null} is returned (rollout evaluation is skipped server-side).
 * </ul>
 *
 * <p>Register your own bean to override the default:
 * <pre>
 * {@literal @}Bean
 * public UserIdProvider userIdProvider(HttpSession session) {
 *     return () -> (String) session.getAttribute("userId");
 * }
 * </pre>
 */
@FunctionalInterface
public interface UserIdProvider {
    /**
     * Returns the current user ID, or {@code null} if unavailable.
     * A null value causes the flag service to skip rollout percentage evaluation.
     */
    String getCurrentUserId();
}
