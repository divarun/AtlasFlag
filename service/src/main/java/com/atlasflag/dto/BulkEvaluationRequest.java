package com.atlasflag.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public class BulkEvaluationRequest {

    @NotEmpty(message = "At least one flag key is required")
    @Size(max = 100, message = "Cannot evaluate more than 100 flags at once")
    private List<String> flagKeys;

    private String environment = "default";
    private String userId;
    private java.util.Map<String, String> attributes;

    public List<String> getFlagKeys()                              { return flagKeys; }
    public void setFlagKeys(List<String> keys)                     { this.flagKeys = keys; }
    public String getEnvironment()                                 { return environment; }
    public void setEnvironment(String env)                         { this.environment = env; }
    public String getUserId()                                      { return userId; }
    public void setUserId(String userId)                           { this.userId = userId; }
    public java.util.Map<String, String> getAttributes()           { return attributes; }
    public void setAttributes(java.util.Map<String, String> attrs) { this.attributes = attrs; }
}
