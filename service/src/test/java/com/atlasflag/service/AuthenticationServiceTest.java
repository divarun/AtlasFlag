package com.atlasflag.service;

import com.atlasflag.domain.User;
import com.atlasflag.repository.UserRepository;
import com.atlasflag.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    @Mock UserRepository userRepository;
    @Mock JwtTokenProvider tokenProvider;
    @Mock PasswordEncoder passwordEncoder;

    @InjectMocks AuthenticationService service;

    @Test
    void authenticate_validCredentials_returnsTokenMap() {
        User user = adminUser("alice", "$2a$encoded");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", "$2a$encoded")).thenReturn(true);
        when(tokenProvider.generateToken("alice", "ADMIN")).thenReturn("jwt.token.here");

        Map<String, String> result = service.authenticate("alice", "secret");

        assertThat(result.get("token")).isEqualTo("jwt.token.here");
        assertThat(result.get("username")).isEqualTo("alice");
        assertThat(result.get("role")).isEqualTo("ADMIN");
        assertThat(result.get("type")).isEqualTo("Bearer");
    }

    @Test
    void authenticate_wrongPassword_throwsBadCredentials() {
        User user = adminUser("alice", "$2a$encoded");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$encoded")).thenReturn(false);

        assertThatThrownBy(() -> service.authenticate("alice", "wrong"))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticate_unknownUser_throwsBadCredentials() {
        when(userRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.authenticate("nobody", "pass"))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticate_nullUsername_throwsBadCredentials() {
        assertThatThrownBy(() -> service.authenticate(null, "pass"))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticate_emptyUsername_throwsBadCredentials() {
        assertThatThrownBy(() -> service.authenticate("  ", "pass"))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticate_nullPassword_throwsBadCredentials() {
        assertThatThrownBy(() -> service.authenticate("alice", null))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void authenticate_stripsWhitespaceFromUsername() {
        User user = adminUser("alice", "$2a$encoded");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass", "$2a$encoded")).thenReturn(true);
        when(tokenProvider.generateToken(any(), any())).thenReturn("token");

        // Username with leading/trailing spaces
        service.authenticate("  alice  ", "pass");

        verify(userRepository).findByUsername("alice"); // trimmed
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private User adminUser(String username, String passwordHash) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.com");
        user.setPasswordHash(passwordHash);
        user.setRole(User.Role.ADMIN);
        return user;
    }
}
