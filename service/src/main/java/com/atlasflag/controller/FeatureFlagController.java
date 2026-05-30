package com.atlasflag.controller;

import com.atlasflag.dto.*;
import com.atlasflag.service.EvaluationAnalyticsService;
import com.atlasflag.service.FeatureFlagService;
import com.atlasflag.service.FlagChangePublisher;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/flags")
public class FeatureFlagController {

    private final FeatureFlagService flagService;
    private final EvaluationAnalyticsService analyticsService;
    private final FlagChangePublisher flagChangePublisher;

    public FeatureFlagController(FeatureFlagService flagService,
                                  EvaluationAnalyticsService analyticsService,
                                  FlagChangePublisher flagChangePublisher) {
        this.flagService = flagService;
        this.analyticsService = analyticsService;
        this.flagChangePublisher = flagChangePublisher;
    }

    @PostMapping
    public ResponseEntity<FeatureFlagDTO> createFlag(@Valid @RequestBody FeatureFlagDTO dto,
                                                     Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(flagService.createFlag(dto, authentication.getName()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FeatureFlagDTO> getFlag(@PathVariable Long id) {
        return flagService.getFlagById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<FeatureFlagDTO>> getAllFlags(
            @RequestParam(required = false, defaultValue = "default") String environment,
            @RequestParam(required = false) String search) {
        return ResponseEntity.ok(flagService.getAllFlags(environment, search));
    }

    @PutMapping("/{id}")
    public ResponseEntity<FeatureFlagDTO> updateFlag(@PathVariable Long id,
                                                     @Valid @RequestBody FeatureFlagDTO dto,
                                                     Authentication authentication) {
        return ResponseEntity.ok(flagService.updateFlag(id, dto, authentication.getName()));
    }

    @PostMapping("/{flagKey}/toggle")
    public ResponseEntity<FeatureFlagDTO> toggleFlag(
            @PathVariable String flagKey,
            @RequestParam(required = false, defaultValue = "default") String environment,
            Authentication authentication) {
        return ResponseEntity.ok(flagService.toggleFlag(flagKey, environment, authentication.getName()));
    }

    @PostMapping("/{id}/promote")
    public ResponseEntity<FeatureFlagDTO> promoteFlag(
            @PathVariable Long id,
            @RequestParam String targetEnvironment,
            Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(flagService.promoteFlag(id, targetEnvironment, authentication.getName()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFlag(@PathVariable Long id, Authentication authentication) {
        flagService.deleteFlag(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }

    // ── Analytics (authenticated) ────────────────────────────────────────────

    @GetMapping("/analytics")
    public ResponseEntity<Map<String, Long>> getBulkAnalytics(
            @RequestParam(required = false, defaultValue = "default") String environment,
            @RequestParam(required = false, defaultValue = "24") int hours) {
        return ResponseEntity.ok(analyticsService.getBulkEvalCounts(environment, Math.min(hours, 168)));
    }

    @GetMapping("/{flagKey}/analytics")
    public ResponseEntity<FlagAnalyticsResponse> getFlagAnalytics(
            @PathVariable String flagKey,
            @RequestParam(required = false, defaultValue = "default") String environment,
            @RequestParam(required = false, defaultValue = "24") int hours) {
        return ResponseEntity.ok(analyticsService.getAnalytics(flagKey, environment, Math.min(hours, 168)));
    }

    // ── SSE stream (public — SDK clients connect without auth) ───────────────

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamFlagChanges(
            @RequestParam(required = false, defaultValue = "default") String environment) {
        return flagChangePublisher.subscribe(environment);
    }

    // ── Public evaluation endpoints (no auth) ────────────────────────────────

    @PostMapping("/evaluate")
    public ResponseEntity<FlagEvaluationResponse> evaluateFlag(
            @Valid @RequestBody FlagEvaluationRequest request) {
        return ResponseEntity.ok(flagService.evaluateFlag(request));
    }

    @PostMapping("/evaluate/bulk")
    public ResponseEntity<Map<String, FlagEvaluationResponse>> evaluateBulk(
            @Valid @RequestBody BulkEvaluationRequest request) {
        return ResponseEntity.ok(flagService.evaluateBulk(request));
    }
}
