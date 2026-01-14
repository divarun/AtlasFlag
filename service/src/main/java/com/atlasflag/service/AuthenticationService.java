package com.atlasflag.service;

import com.atlasflag.domain.User;
import com.atlasflag.repository.UserRepository;
import com.atlasflag.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Authentication service.
 * 
 * NOTE: For MVP, this implements basic username validation.
 * In production, this should:
 * - Validate passwords (use BCrypt or similar)
 * - Implement rate limiting
 * - Add account lockout after failed attempts
 * - Support password reset
 */
@Service
public class AuthenticationService {
    
    private static final Logger logger = LoggerFactory.getLogger(AuthenticationService.class);
    
    private final UserRepository userRepository;
    private final JwtTokenProvider tokenProvider;
    
    public AuthenticationService(UserRepository userRepository, JwtTokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.tokenProvider = tokenProvider;
    }
    
    /**
     * Authenticate user and generate JWT token.
     * 
     * @param username Username
     * @param password Password (currently not validated for MVP)
     * @return JWT token
     * @throws BadCredentialsException if authentication fails
     */
    public String authenticate(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            throw new BadCredentialsException("Username is required");
        }
        
        // For MVP: Just validate username exists
        // TODO: Add password validation in production
        Optional<User> userOpt = userRepository.findByUsername(username.trim());
        
        if (userOpt.isEmpty()) {
            logger.warn("Authentication failed: user not found - {}", username);
            throw new BadCredentialsException("Invalid credentials");
        }
        
        User user = userOpt.get();
        
        // TODO: Validate password
        // if (!passwordEncoder.matches(password, user.getPasswordHash())) {
        //     throw new BadCredentialsException("Invalid credentials");
        // }
        
        logger.info("User authenticated successfully: {}", username);
        return tokenProvider.generateToken(user.getUsername());
    }
}
