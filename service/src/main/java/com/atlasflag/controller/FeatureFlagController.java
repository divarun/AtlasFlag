package com.atlasflag.controller;

import com.atlasflag.dto.FeatureFlagDTO;
import com.atlasflag.dto.FlagEvaluationRequest;
import com.atlasflag.dto.FlagEvaluationResponse;
import com.atlasflag.service.FeatureFlagService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/flags")
public class FeatureFlagController {
    
    private final FeatureFlagService flagService;
    
    public FeatureFlagController(FeatureFlagService flagService) {
        this.flagService = flagService;
    }
    
    @PostMapping
    public ResponseEntity<FeatureFlagDTO> createFlag(@Valid @RequestBody FeatureFlagDTO dto,
                                                     Authentication authentication) {
        String userId = authentication.getName();
        FeatureFlagDTO created = flagService.createFlag(dto, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<FeatureFlagDTO> getFlag(@PathVariable Long id) {
        return flagService.getFlagById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
    
    @GetMapping
    public ResponseEntity<List<FeatureFlagDTO>> getAllFlags(
            @RequestParam(required = false, defaultValue = "default") String environment) {
        List<FeatureFlagDTO> flags = flagService.getAllFlags(environment);
        return ResponseEntity.ok(flags);
    }
    
    @PutMapping("/{id}")
    public ResponseEntity<FeatureFlagDTO> updateFlag(@PathVariable Long id,
                                                     @Valid @RequestBody FeatureFlagDTO dto,
                                                     Authentication authentication) {
        String userId = authentication.getName();
        FeatureFlagDTO updated = flagService.updateFlag(id, dto, userId);
        return ResponseEntity.ok(updated);
    }
    
    @PostMapping("/{flagKey}/toggle")
    public ResponseEntity<FeatureFlagDTO> toggleFlag(@PathVariable String flagKey,
                                                     @RequestParam(required = false, defaultValue = "default") String environment,
                                                     Authentication authentication) {
        String userId = authentication.getName();
        FeatureFlagDTO updated = flagService.toggleFlag(flagKey, environment, userId);
        return ResponseEntity.ok(updated);
    }
    
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFlag(@PathVariable Long id, Authentication authentication) {
        String userId = authentication.getName();
        flagService.deleteFlag(id, userId);
        return ResponseEntity.noContent().build();
    }
    
    @PostMapping("/evaluate")
    public ResponseEntity<FlagEvaluationResponse> evaluateFlag(@Valid @RequestBody FlagEvaluationRequest request) {
        FlagEvaluationResponse response = flagService.evaluateFlag(request);
        return ResponseEntity.ok(response);
    }
}
