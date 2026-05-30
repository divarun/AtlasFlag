package com.atlasflag.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.*;

class JwtTokenProviderTest {

    private static final String VALID_SECRET = "test-secret-key-that-is-at-least-32-characters-long";
    private static final long EXPIRATION_MS = 3_600_000L; // 1 hour
    private static final long EXPIRED_MS    = -1_000L;    // already expired

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        provider = createProvider(VALID_SECRET, EXPIRATION_MS);
    }

    @Test
    void generateToken_containsUsernameAndRole() {
        String token = provider.generateToken("alice", "ADMIN");

        assertThat(provider.getUsernameFromToken(token)).isEqualTo("alice");
        assertThat(provider.getRoleFromToken(token)).isEqualTo("ADMIN");
    }

    @Test
    void isTokenValid_freshToken_returnsTrue() {
        String token = provider.generateToken("bob", "USER");

        assertThat(provider.isTokenValid(token)).isTrue();
    }

    @Test
    void isTokenValid_expiredToken_returnsFalse() throws Exception {
        JwtTokenProvider expiredProvider = createProvider(VALID_SECRET, EXPIRED_MS);
        String token = expiredProvider.generateToken("bob", "USER");

        assertThat(provider.isTokenValid(token)).isFalse();
    }

    @Test
    void isTokenValid_tamperedToken_returnsFalse() {
        String token = provider.generateToken("alice", "ADMIN");
        String tampered = token.substring(0, token.length() - 4) + "xxxx";

        assertThat(provider.isTokenValid(tampered)).isFalse();
    }

    @Test
    void isTokenValid_wrongSecret_returnsFalse() throws Exception {
        JwtTokenProvider otherProvider = createProvider(
            "completely-different-secret-that-is-also-long-enough", EXPIRATION_MS);
        String tokenFromOther = otherProvider.generateToken("alice", "ADMIN");

        assertThat(provider.isTokenValid(tokenFromOther)).isFalse();
    }

    @Test
    void isTokenValid_nullOrEmpty_returnsFalse() {
        assertThat(provider.isTokenValid(null)).isFalse();
        assertThat(provider.isTokenValid("")).isFalse();
        assertThat(provider.isTokenValid("not.a.jwt")).isFalse();
    }

    @Test
    void validateAndCacheKey_secretTooShort_throwsIllegalState() throws Exception {
        // Method.invoke() wraps the real exception in InvocationTargetException; unwrap it
        Throwable thrown = catchThrowable(() -> createProvider("short", EXPIRATION_MS));
        assertThat(thrown).isNotNull();
        Throwable cause = thrown.getCause(); // the real IllegalStateException
        assertThat(cause)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JWT secret must be at least");
    }

    @Test
    void getRoleFromToken_differentRoles_preservedCorrectly() {
        for (String role : new String[]{"ADMIN", "USER", "VIEWER"}) {
            String token = provider.generateToken("user", role);
            assertThat(provider.getRoleFromToken(token)).isEqualTo(role);
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private JwtTokenProvider createProvider(String secret, long expiration) throws Exception {
        JwtTokenProvider p = new JwtTokenProvider();
        ReflectionTestUtils.setField(p, "jwtSecret", secret);
        ReflectionTestUtils.setField(p, "jwtExpiration", expiration);
        Method init = JwtTokenProvider.class.getDeclaredMethod("validateAndCacheKey");
        init.setAccessible(true);
        init.invoke(p);
        return p;
    }
}
