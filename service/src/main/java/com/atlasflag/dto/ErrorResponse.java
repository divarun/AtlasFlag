package com.atlasflag.dto;

import java.time.Instant;

public class ErrorResponse {
    private String error;
    private String errorId;
    private Instant timestamp;
    
    public ErrorResponse(String error, String errorId) {
        this.error = error;
        this.errorId = errorId;
        this.timestamp = Instant.now();
    }
    
    public String getError() {
        return error;
    }
    
    public void setError(String error) {
        this.error = error;
    }
    
    public String getErrorId() {
        return errorId;
    }
    
    public void setErrorId(String errorId) {
        this.errorId = errorId;
    }
    
    public Instant getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
