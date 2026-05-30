package com.atlasflag.controller;

import com.atlasflag.dto.WebhookDTO;
import com.atlasflag.service.WebhookService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/webhooks")
@PreAuthorize("hasRole('ADMIN')")
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @GetMapping
    public ResponseEntity<List<WebhookDTO>> list() {
        return ResponseEntity.ok(webhookService.listAll());
    }

    @PostMapping
    public ResponseEntity<WebhookDTO> create(@Valid @RequestBody WebhookDTO dto,
                                             Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(webhookService.create(dto, authentication.getName()));
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<WebhookDTO> toggle(@PathVariable Long id) {
        return ResponseEntity.ok(webhookService.toggle(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        webhookService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
