package com.atlasflag.dto;

public class FlagEvaluationResponse {
    
    private String flagKey;
    
    private Boolean enabled;
    
    private String reason; // e.g., "FLAG_ENABLED", "ROLLOUT_PERCENTAGE", "DEFAULT_VALUE"
    
    // Getters and Setters
    public String getFlagKey() {
        return flagKey;
    }
    
    public void setFlagKey(String flagKey) {
        this.flagKey = flagKey;
    }
    
    public Boolean getEnabled() {
        return enabled;
    }
    
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
    
    public String getReason() {
        return reason;
    }
    
    public void setReason(String reason) {
        this.reason = reason;
    }
}
