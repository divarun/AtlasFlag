package com.atlasflag.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "audit_logs", indexes = {
    @Index(name = "idx_entity_type", columnList = "entity_type,entity_id"),
    @Index(name = "idx_user", columnList = "user_id"),
    @Index(name = "idx_timestamp", columnList = "timestamp")
})
public class AuditLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @NotBlank
    @Column(name = "entity_type", nullable = false)
    private String entityType; // e.g., "FeatureFlag", "Configuration"
    
    @Column(name = "entity_id")
    private Long entityId;
    
    @NotBlank
    @Column(name = "action", nullable = false)
    private String action; // e.g., "CREATE", "UPDATE", "DELETE", "ENABLE", "DISABLE"
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(name = "user_email")
    private String userEmail;
    
    @Column(name = "changes", columnDefinition = "TEXT")
    private String changes; // JSON representation of changes
    
    @NotNull
    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;
    
    @Column(name = "ip_address")
    private String ipAddress;
    
    @PrePersist
    protected void onCreate() {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getEntityType() {
        return entityType;
    }
    
    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }
    
    public Long getEntityId() {
        return entityId;
    }
    
    public void setEntityId(Long entityId) {
        this.entityId = entityId;
    }
    
    public String getAction() {
        return action;
    }
    
    public void setAction(String action) {
        this.action = action;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getUserEmail() {
        return userEmail;
    }
    
    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }
    
    public String getChanges() {
        return changes;
    }
    
    public void setChanges(String changes) {
        this.changes = changes;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getIpAddress() {
        return ipAddress;
    }
    
    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AuditLog auditLog = (AuditLog) o;
        return Objects.equals(id, auditLog.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
