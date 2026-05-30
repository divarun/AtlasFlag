package com.atlasflag.starter;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Extracts the current userId from Spring Security's authentication context.
 * Registered automatically when Spring Security is on the classpath.
 */
public class SecurityContextUserIdProvider implements UserIdProvider {

    @Override
    public String getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                    && !"anonymousUser".equals(auth.getPrincipal())) {
                return auth.getName();
            }
        } catch (Exception ignored) {
            // SecurityContext not set up (e.g. background threads, tests)
        }
        return null;
    }
}
