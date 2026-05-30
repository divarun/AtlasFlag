package com.atlasflag.dto;

import com.atlasflag.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public class UserDTO {

    private Long id;

    @NotBlank
    private String username;

    @Email
    @NotBlank
    private String email;

    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    private User.Role role;
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public User.Role getRole() { return role; }
    public void setRole(User.Role role) { this.role = role; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
