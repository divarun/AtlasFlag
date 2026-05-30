package com.atlasflag.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "webhooks",
    indexes = { @Index(name = "idx_webhooks_enabled", columnList = "enabled") })
public class Webhook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Column(name = "secret", nullable = false)
    private String secret;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId()             { return id; }
    public String getUrl()          { return url; }
    public void setUrl(String url)  { this.url = url; }
    public String getSecret()       { return secret; }
    public void setSecret(String s) { this.secret = s; }
    public Boolean getEnabled()     { return enabled; }
    public void setEnabled(Boolean e) { this.enabled = e; }
    public String getCreatedBy()    { return createdBy; }
    public void setCreatedBy(String u) { this.createdBy = u; }
    public Instant getCreatedAt()   { return createdAt; }
}
