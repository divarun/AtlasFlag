package com.atlasflag.service;

import com.atlasflag.domain.User;
import com.atlasflag.repository.UserRepository;
import com.atlasflag.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

@Service
public class AuthenticationService {

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationService.class);

    private final UserRepository userRepository;
    private final JwtTokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;

    public AuthenticationService(UserRepository userRepository, JwtTokenProvider tokenProvider,
                                 PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.tokenProvider = tokenProvider;
        this.passwordEncoder = passwordEncoder;
    }

    public Map<String, String> authenticate(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            throw new BadCredentialsException("Username is required");
        }
        if (password == null || password.isEmpty()) {
            throw new BadCredentialsException("Password is required");
        }

        Optional<User> userOpt = userRepository.findByUsername(username.trim());
        if (userOpt.isEmpty()) {
            logger.warn("Authentication failed: user not found - {}", username);
            throw new BadCredentialsException("Invalid credentials");
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            logger.warn("Authentication failed: wrong password for user - {}", username);
            throw new BadCredentialsException("Invalid credentials");
        }

        logger.info("User authenticated successfully: {}", username);
        String token = tokenProvider.generateToken(user.getUsername(), user.getRole().name());
        return Map.of(
            "token", token,
            "type", "Bearer",
            "username", user.getUsername(),
            "role", user.getRole().name()
        );
    }
}
