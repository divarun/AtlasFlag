package com.atlasflag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public class WebhookDTO {

    private Long id;

    @NotBlank(message = "URL is required")
    private String url;

    @NotBlank(message = "Secret is required")
    @Size(min = 16, message = "Secret must be at least 16 characters")
    private String secret;

    private Boolean enabled;
    private String createdBy;
    private Instant createdAt;

    public Long getId()             { return id; }
    public void setId(Long id)      { this.id = id; }
    public String getUrl()          { return url; }
    public void setUrl(String url)  { this.url = url; }
    public String getSecret()       { return secret; }
    public void setSecret(String s) { this.secret = s; }
    public Boolean getEnabled()     { return enabled; }
    public void setEnabled(Boolean e) { this.enabled = e; }
    public String getCreatedBy()    { return createdBy; }
    public void setCreatedBy(String u) { this.createdBy = u; }
    public Instant getCreatedAt()   { return createdAt; }
    public void setCreatedAt(Instant t) { this.createdAt = t; }
}
