package com.atlasflag.service;

import com.atlasflag.domain.FeatureFlag;
import com.atlasflag.domain.Webhook;
import com.atlasflag.dto.WebhookDTO;
import com.atlasflag.repository.WebhookRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
public class WebhookService {

    private static final Logger logger = LoggerFactory.getLogger(WebhookService.class);

    private final WebhookRepository webhookRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WebhookService(WebhookRepository webhookRepository, ObjectMapper objectMapper) {
        this.webhookRepository = webhookRepository;
        this.objectMapper = objectMapper.copy()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    @Async
    public void dispatch(String event, FeatureFlag flag, String triggeredBy) {
        List<Webhook> webhooks = webhookRepository.findByEnabledTrue();
        if (webhooks.isEmpty()) return;

        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                "event",       event,
                "flagKey",     flag.getFlagKey(),
                "environment", flag.getEnvironment(),
                "enabled",     flag.getEnabled() != null && flag.getEnabled(),
                "triggeredBy", triggeredBy,
                "timestamp",   Instant.now().toString()
            ));
        } catch (Exception e) {
            logger.error("Failed to serialize webhook payload for event {}", event, e);
            return;
        }

        for (Webhook webhook : webhooks) {
            try {
                String signature = sign(payload, webhook.getSecret());
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhook.getUrl()))
                    .header("Content-Type", "application/json")
                    .header("X-AtlasFlag-Event", event)
                    .header("X-AtlasFlag-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(Duration.ofSeconds(5))
                    .build();

                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    logger.debug("Webhook dispatched to {} for event {}", webhook.getUrl(), event);
                } else {
                    logger.warn("Webhook to {} returned HTTP {} for event {}", webhook.getUrl(), response.statusCode(), event);
                }
            } catch (Exception e) {
                logger.error("Failed to dispatch webhook to {} for event {}", webhook.getUrl(), event, e);
            }
        }
    }

    private String sign(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    public WebhookDTO create(WebhookDTO dto, String createdBy) {
        com.atlasflag.domain.Webhook webhook = new com.atlasflag.domain.Webhook();
        webhook.setUrl(dto.getUrl());
        webhook.setSecret(dto.getSecret());
        webhook.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : true);
        webhook.setCreatedBy(createdBy);
        return toDTO(webhookRepository.save(webhook));
    }

    public List<WebhookDTO> listAll() {
        return webhookRepository.findAll().stream().map(this::toSafeDTO).toList();
    }

    public WebhookDTO toggle(Long id) {
        Webhook webhook = webhookRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Webhook not found: " + id));
        webhook.setEnabled(!webhook.getEnabled());
        return toSafeDTO(webhookRepository.save(webhook));
    }

    public void delete(Long id) {
        if (!webhookRepository.existsById(id)) {
            throw new IllegalArgumentException("Webhook not found: " + id);
        }
        webhookRepository.deleteById(id);
    }

    private WebhookDTO toDTO(Webhook w) {
        WebhookDTO dto = new WebhookDTO();
        dto.setId(w.getId());
        dto.setUrl(w.getUrl());
        dto.setSecret(w.getSecret()); // only returned on creation
        dto.setEnabled(w.getEnabled());
        dto.setCreatedBy(w.getCreatedBy());
        dto.setCreatedAt(w.getCreatedAt());
        return dto;
    }

    private WebhookDTO toSafeDTO(Webhook w) {
        WebhookDTO dto = toDTO(w);
        dto.setSecret("••••••••"); // never expose the secret after creation
        return dto;
    }
}
