package com.atlasflag.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;

@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);
    private static final int MIN_SECRET_LENGTH = 32;
    private static final String ROLE_CLAIM = "role";

    @Value("${atlasflag.jwt.secret}")
    private String jwtSecret;

    @Value("${atlasflag.jwt.expiration}")
    private long jwtExpiration;

    private SecretKey signingKey;

    @PostConstruct
    private void validateAndCacheKey() {
        if (jwtSecret == null || jwtSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                String.format("JWT secret must be at least %d bytes. Current length: %d. Set JWT_SECRET env var.",
                    MIN_SECRET_LENGTH, jwtSecret != null ? jwtSecret.length() : 0));
        }
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String username, String role) {
        Date now = new Date();
        return Jwts.builder()
            .subject(username)
            .claim(ROLE_CLAIM, role)
            .issuedAt(now)
            .expiration(new Date(now.getTime() + jwtExpiration))
            .signWith(signingKey)
            .compact();
    }

    public String getUsernameFromToken(String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    public String getRoleFromToken(String token) {
        return getClaimFromToken(token, claims -> claims.get(ROLE_CLAIM, String.class));
    }

    public Boolean isTokenValid(String token) {
        try {
            Claims claims = getAllClaimsFromToken(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            logger.debug("Token validation failed", e);
            return false;
        }
    }

    public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
        return claimsResolver.apply(getAllClaimsFromToken(token));
    }

    private Claims getAllClaimsFromToken(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
