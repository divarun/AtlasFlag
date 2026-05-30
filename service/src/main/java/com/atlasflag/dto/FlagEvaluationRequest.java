package com.atlasflag.dto;

import jakarta.validation.constraints.NotBlank;

public class FlagEvaluationRequest {
    
    @NotBlank(message = "Flag key is required")
    private String flagKey;
    
    private String environment = "default";
    
    private String userId;

    private java.util.Map<String, String> attributes;

    // Getters and Setters
    public String getFlagKey() {
        return flagKey;
    }
    
    public void setFlagKey(String flagKey) {
        this.flagKey = flagKey;
    }
    
    public String getEnvironment() {
        return environment;
    }
    
    public void setEnvironment(String environment) {
        this.environment = environment;
    }
    
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public java.util.Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(java.util.Map<String, String> attributes) {
        this.attributes = attributes;
    }
}
