package com.atlasflag.dto;

public class FlagEvaluationResponse {
    
    private String flagKey;
    
    private Boolean enabled;
    
    private String reason;

    private String value; // non-null for STRING/NUMBER/JSON flag types when enabled

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

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }
}
