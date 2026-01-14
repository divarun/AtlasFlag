package com.atlasflag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.time.Instant;

public class FeatureFlagDTO {
    
    private Long id;
    
    @NotBlank(message = "Flag key is required")
    private String flagKey;
    
    @NotBlank(message = "Name is required")
    private String name;
    
    private String description;
    
    private Boolean enabled;
    
    @Min(value = 0, message = "Rollout percentage must be between 0 and 100")
    @Max(value = 100, message = "Rollout percentage must be between 0 and 100")
    private Integer rolloutPercentage;
    
    private String environment;
    
    private Boolean defaultValue;
    
    private String createdBy;
    
    private Instant createdAt;
    
    private String updatedBy;
    
    private Instant updatedAt;
    
    private Long version;
    
    // Constructors
    public FeatureFlagDTO() {}
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getFlagKey() {
        return flagKey;
    }
    
    public void setFlagKey(String flagKey) {
        this.flagKey = flagKey;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public Boolean getEnabled() {
        return enabled;
    }
    
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
    
    public Integer getRolloutPercentage() {
        return rolloutPercentage;
    }
    
    public void setRolloutPercentage(Integer rolloutPercentage) {
        this.rolloutPercentage = rolloutPercentage;
    }
    
    public String getEnvironment() {
        return environment;
    }
    
    public void setEnvironment(String environment) {
        this.environment = environment;
    }
    
    public Boolean getDefaultValue() {
        return defaultValue;
    }
    
    public void setDefaultValue(Boolean defaultValue) {
        this.defaultValue = defaultValue;
    }
    
    public String getCreatedBy() {
        return createdBy;
    }
    
    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public String getUpdatedBy() {
        return updatedBy;
    }
    
    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
    
    public Instant getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public Long getVersion() {
        return version;
    }
    
    public void setVersion(Long version) {
        this.version = version;
    }
}
