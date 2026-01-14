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
    private static final int MIN_SECRET_LENGTH = 32; // 256 bits = 32 bytes
    
    @Value("${atlasflag.jwt.secret}")
    private String jwtSecret;
    
    @Value("${atlasflag.jwt.expiration}")
    private long jwtExpiration;
    
    private SecretKey signingKey;
    
    @PostConstruct
    private void validateAndCacheKey() {
        if (jwtSecret == null || jwtSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                String.format("JWT secret must be at least %d bytes (256 bits). " +
                    "Current length: %d. Set JWT_SECRET environment variable.",
                    MIN_SECRET_LENGTH, jwtSecret != null ? jwtSecret.length() : 0));
        }
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        logger.info("JWT secret validated and cached successfully");
    }
    
    private SecretKey getSigningKey() {
        if (signingKey == null) {
            throw new IllegalStateException("JWT signing key not initialized");
        }
        return signingKey;
    }
    
    public String generateToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpiration);
        
        return Jwts.builder()
            .subject(username)
            .issuedAt(now)
            .expiration(expiryDate)
            .signWith(getSigningKey())
            .compact();
    }
    
    public String getUsernameFromToken(String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }
    
    public Date getExpirationDateFromToken(String token) {
        return getClaimFromToken(token, Claims::getExpiration);
    }
    
    public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = getAllClaimsFromToken(token);
        return claimsResolver.apply(claims);
    }
    
    private Claims getAllClaimsFromToken(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
    
    /**
     * Validates token signature and expiration only.
     * Does not validate against username (circular validation).
     */
    public Boolean isTokenValid(String token) {
        try {
            getAllClaimsFromToken(token); // Validates signature
            return !isTokenExpired(token);
        } catch (Exception e) {
            logger.debug("Token validation failed", e);
            return false;
        }
    }
    
    /**
     * @deprecated Use isTokenValid(String) instead. This method has circular validation logic.
     */
    @Deprecated
    public Boolean validateToken(String token, String username) {
        if (!isTokenValid(token)) {
            return false;
        }
        final String tokenUsername = getUsernameFromToken(token);
        return tokenUsername.equals(username);
    }
    
    private Boolean isTokenExpired(String token) {
        try {
            final Date expiration = getExpirationDateFromToken(token);
            return expiration.before(new Date());
        } catch (Exception e) {
            logger.debug("Failed to check token expiration", e);
            return true; // If we can't check, consider expired
        }
    }
}
