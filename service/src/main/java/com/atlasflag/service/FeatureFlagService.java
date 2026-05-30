package com.atlasflag.service;

import com.atlasflag.domain.FeatureFlag;
import com.atlasflag.dto.BulkEvaluationRequest;
import com.atlasflag.dto.FeatureFlagDTO;
import com.atlasflag.dto.FlagEvaluationRequest;
import com.atlasflag.dto.FlagEvaluationResponse;
import com.atlasflag.exception.ConflictException;
import com.atlasflag.repository.FeatureFlagRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FeatureFlagService {

    private static final Logger logger = LoggerFactory.getLogger(FeatureFlagService.class);
    private static final int MIN_ROLLOUT = 0;
    private static final int MAX_ROLLOUT = 100;

    private final FeatureFlagRepository flagRepository;
    private final AuditService auditService;
    private final WebhookService webhookService;
    private final ObjectMapper objectMapper;
    private final EvaluationAnalyticsService analyticsService;
    private final FlagChangePublisher flagChangePublisher;

    public FeatureFlagService(FeatureFlagRepository flagRepository, AuditService auditService,
                               WebhookService webhookService, ObjectMapper objectMapper,
                               EvaluationAnalyticsService analyticsService,
                               FlagChangePublisher flagChangePublisher) {
        this.flagRepository = flagRepository;
        this.auditService = auditService;
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
        this.analyticsService = analyticsService;
        this.flagChangePublisher = flagChangePublisher;
    }

    @Transactional
    @CacheEvict(value = "flags", key = "'flag:' + #dto.flagKey + ':env:' + #dto.environment")
    public FeatureFlagDTO createFlag(FeatureFlagDTO dto, String userId) {
        validateFlagDTO(dto);
        if (dto.getEnvironment() == null || dto.getEnvironment().isBlank()) {
            throw new IllegalArgumentException("Environment is required");
        }
        String environment = dto.getEnvironment();

        if (flagRepository.existsByFlagKeyAndEnvironment(dto.getFlagKey(), environment)) {
            throw new IllegalArgumentException("Flag '" + dto.getFlagKey() + "' already exists in " + environment);
        }

        FeatureFlag flag = new FeatureFlag();
        flag.setFlagKey(dto.getFlagKey());
        flag.setName(dto.getName());
        flag.setDescription(dto.getDescription());
        flag.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : false);
        flag.setRolloutPercentage(dto.getRolloutPercentage());
        flag.setEnvironment(environment);
        flag.setDefaultValue(dto.getDefaultValue() != null ? dto.getDefaultValue() : false);
        flag.setFlagType(dto.getFlagType() != null ? dto.getFlagType() : "BOOLEAN");
        flag.setStringValue(dto.getStringValue());
        flag.setTargetingRules(dto.getTargetingRules());
        flag.setCreatedBy(userId);

        FeatureFlag saved = flagRepository.save(flag);
        auditService.logAction("FeatureFlag", saved.getId(), "CREATE", userId, null, convertToJson(saved));
        webhookService.dispatch("FLAG_CREATED", saved, userId);
        publishFlagChange(saved, false);
        return toDTO(saved);
    }

    @Transactional
    @CachePut(value = "flags", key = "'flag:' + #result.flagKey + ':env:' + #result.environment")
    public FeatureFlagDTO updateFlag(Long id, FeatureFlagDTO dto, String userId) {
        validateFlagDTO(dto);
        FeatureFlag flag = flagRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Flag not found: " + id));

        String oldValue = convertToJson(flag);
        flag.setName(dto.getName());
        flag.setDescription(dto.getDescription());
        flag.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : flag.getEnabled());
        flag.setRolloutPercentage(dto.getRolloutPercentage());
        flag.setDefaultValue(dto.getDefaultValue() != null ? dto.getDefaultValue() : flag.getDefaultValue());
        flag.setFlagType(dto.getFlagType() != null ? dto.getFlagType() : flag.getFlagType());
        flag.setStringValue(dto.getStringValue());
        flag.setTargetingRules(dto.getTargetingRules());
        flag.setUpdatedBy(userId);

        try {
            FeatureFlag saved = flagRepository.save(flag);
            auditService.logAction("FeatureFlag", saved.getId(), "UPDATE", userId, oldValue, convertToJson(saved));
            webhookService.dispatch("FLAG_UPDATED", saved, userId);
            publishFlagChange(saved, false);
            return toDTO(saved);
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("Flag was modified by another user. Please refresh and try again.");
        }
    }

    @Transactional
    @CacheEvict(value = "flags", key = "'flag:' + #flagKey + ':env:' + #environment")
    public FeatureFlagDTO toggleFlag(String flagKey, String environment, String userId) {
        FeatureFlag flag = flagRepository.findByFlagKeyAndEnvironment(flagKey, environment)
            .orElseThrow(() -> new IllegalArgumentException("Flag not found: " + flagKey));

        String oldValue = convertToJson(flag);
        flag.setEnabled(!flag.getEnabled());
        flag.setUpdatedBy(userId);

        try {
            FeatureFlag saved = flagRepository.save(flag);
            String action = saved.getEnabled() ? "ENABLE" : "DISABLE";
            auditService.logAction("FeatureFlag", saved.getId(), action, userId, oldValue, convertToJson(saved));
            webhookService.dispatch(saved.getEnabled() ? "FLAG_ENABLED" : "FLAG_DISABLED", saved, userId);
            publishFlagChange(saved, false);
            return toDTO(saved);
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("Flag was modified by another user. Please refresh and try again.");
        }
    }

    /**
     * Promote a flag's configuration to another environment.
     * The promoted flag starts disabled in the target environment — enable it when ready.
     */
    @Transactional
    public FeatureFlagDTO promoteFlag(Long id, String targetEnvironment, String userId) {
        if (targetEnvironment == null || targetEnvironment.isBlank()) {
            throw new IllegalArgumentException("Target environment is required");
        }

        FeatureFlag source = flagRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Flag not found: " + id));

        if (source.getEnvironment().equalsIgnoreCase(targetEnvironment)) {
            throw new IllegalArgumentException("Flag is already in " + targetEnvironment);
        }
        if (flagRepository.existsByFlagKeyAndEnvironment(source.getFlagKey(), targetEnvironment)) {
            throw new ConflictException("Flag '" + source.getFlagKey() + "' already exists in " + targetEnvironment
                + ". Edit it directly if you want to update it.");
        }

        FeatureFlag promoted = new FeatureFlag();
        promoted.setFlagKey(source.getFlagKey());
        promoted.setName(source.getName());
        promoted.setDescription(source.getDescription());
        promoted.setEnabled(false); // always start disabled — operator enables when ready
        promoted.setRolloutPercentage(source.getRolloutPercentage());
        promoted.setEnvironment(targetEnvironment);
        promoted.setDefaultValue(source.getDefaultValue());
        promoted.setFlagType(source.getFlagType());
        promoted.setStringValue(source.getStringValue());
        promoted.setTargetingRules(source.getTargetingRules());
        promoted.setCreatedBy(userId);

        FeatureFlag saved = flagRepository.save(promoted);
        auditService.logAction("FeatureFlag", saved.getId(), "PROMOTE", userId,
            "{\"from\":\"" + source.getEnvironment() + "\"}",
            "{\"to\":\"" + targetEnvironment + "\"}");
        webhookService.dispatch("FLAG_PROMOTED", saved, userId);
        publishFlagChange(saved, false);
        return toDTO(saved);
    }

    @Cacheable(value = "flags", key = "'flag:' + #flagKey + ':env:' + #environment")
    public Optional<FeatureFlag> getFlag(String flagKey, String environment) {
        return flagRepository.findByFlagKeyAndEnvironment(flagKey, environment);
    }

    public List<FeatureFlagDTO> getAllFlags(String environment, String search) {
        List<FeatureFlag> flags = (search != null && !search.isBlank())
            ? flagRepository.findByEnvironmentAndFlagKeyContainingIgnoreCase(environment, search)
            : flagRepository.findByEnvironment(environment);
        return flags.stream().map(this::toDTO).collect(Collectors.toList());
    }

    public Optional<FeatureFlagDTO> getFlagById(Long id) {
        return flagRepository.findById(id).map(this::toDTO);
    }

    public FlagEvaluationResponse evaluateFlag(FlagEvaluationRequest request) {
        FlagEvaluationResponse response = new FlagEvaluationResponse();
        response.setFlagKey(request.getFlagKey());

        Optional<FeatureFlag> flagOpt = getFlag(request.getFlagKey(), request.getEnvironment());
        if (flagOpt.isEmpty()) {
            response.setEnabled(false);
            response.setReason("FLAG_NOT_FOUND");
            return response;
        }

        FeatureFlag flag = flagOpt.get();

        if (!flag.getEnabled()) {
            response.setEnabled(flag.getDefaultValue());
            response.setReason("FLAG_DISABLED");
            response.setValue(flag.getStringValue());
            analyticsService.record(flag.getFlagKey(), flag.getEnvironment(), Boolean.TRUE.equals(flag.getDefaultValue()));
            return response;
        }

        // Evaluate targeting rules when defined and attributes are provided
        if (flag.getTargetingRules() != null && !flag.getTargetingRules().isBlank()
                && request.getAttributes() != null && !request.getAttributes().isEmpty()) {
            boolean matches = evaluateTargetingRules(flag.getTargetingRules(), request.getAttributes());
            if (!matches) {
                response.setEnabled(flag.getDefaultValue());
                response.setReason("TARGETING_NO_MATCH");
                response.setValue(flag.getStringValue());
                analyticsService.record(flag.getFlagKey(), flag.getEnvironment(), Boolean.TRUE.equals(flag.getDefaultValue()));
                return response;
            }
            // Targeting matched — fall through to rollout / FLAG_ENABLED
        }

        if (flag.getRolloutPercentage() != null && request.getUserId() != null) {
            int hash = (request.getUserId().hashCode() & 0x7FFFFFFF) % 100;
            boolean inRollout = hash < flag.getRolloutPercentage();
            response.setEnabled(inRollout);
            response.setReason(inRollout ? "ROLLOUT_PERCENTAGE" : "ROLLOUT_EXCLUDED");
            if (inRollout) response.setValue(flag.getStringValue());
            analyticsService.record(flag.getFlagKey(), flag.getEnvironment(), inRollout);
            return response;
        }

        response.setEnabled(true);
        response.setReason("FLAG_ENABLED");
        response.setValue(flag.getStringValue());
        analyticsService.record(flag.getFlagKey(), flag.getEnvironment(), true);
        return response;
    }

    public Map<String, FlagEvaluationResponse> evaluateBulk(BulkEvaluationRequest request) {
        return request.getFlagKeys().stream()
            .distinct()
            .collect(Collectors.toMap(
                flagKey -> flagKey,
                flagKey -> {
                    FlagEvaluationRequest single = new FlagEvaluationRequest();
                    single.setFlagKey(flagKey);
                    single.setEnvironment(request.getEnvironment());
                    single.setUserId(request.getUserId());
                    single.setAttributes(request.getAttributes());
                    return evaluateFlag(single);
                }
            ));
    }

    @Transactional
    public void deleteFlag(Long id, String userId) {
        FeatureFlag flag = flagRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Flag not found: " + id));

        String oldValue = convertToJson(flag);
        webhookService.dispatch("FLAG_DELETED", flag, userId);
        publishFlagChange(flag, true);
        flagRepository.delete(flag);
        auditService.logAction("FeatureFlag", id, "DELETE", userId, oldValue, null);

        evictCacheEntry(flag.getFlagKey(), flag.getEnvironment());
    }

    private void evictCacheEntry(String flagKey, String environment) {
        logger.debug("Evicting cache for flag '{}' in '{}'", flagKey, environment);
    }

    // ── Targeting Rules ──────────────────────────────────────────────────────

    private boolean evaluateTargetingRules(String rulesJson, Map<String, String> attributes) {
        try {
            JsonNode root = objectMapper.readTree(rulesJson);
            String matchMode = root.path("match").asText("all");
            JsonNode rules = root.path("rules");
            if (!rules.isArray() || rules.size() == 0) return false;

            boolean matchAll = "all".equalsIgnoreCase(matchMode);
            for (JsonNode rule : rules) {
                String attribute = rule.path("attribute").asText();
                String operator = rule.path("operator").asText();
                String ruleValue = rule.path("value").asText();
                boolean result = evaluateSingleRule(attributes.get(attribute), operator, ruleValue);
                if (matchAll && !result) return false;
                if (!matchAll && result) return true;
            }
            return matchAll;
        } catch (Exception e) {
            logger.warn("Failed to evaluate targeting rules: {}", e.getMessage());
            return false;
        }
    }

    private boolean evaluateSingleRule(String userValue, String operator, String ruleValue) {
        if (userValue == null) return false;
        return switch (operator) {
            case "eq"         -> userValue.equalsIgnoreCase(ruleValue);
            case "neq"        -> !userValue.equalsIgnoreCase(ruleValue);
            case "contains"   -> userValue.toLowerCase().contains(ruleValue.toLowerCase());
            case "startsWith" -> userValue.toLowerCase().startsWith(ruleValue.toLowerCase());
            case "endsWith"   -> userValue.toLowerCase().endsWith(ruleValue.toLowerCase());
            case "in"         -> Arrays.stream(ruleValue.split(","))
                                    .map(String::trim).anyMatch(v -> v.equalsIgnoreCase(userValue));
            case "notIn"      -> Arrays.stream(ruleValue.split(","))
                                    .map(String::trim).noneMatch(v -> v.equalsIgnoreCase(userValue));
            case "gt"         -> parseDouble(userValue) > parseDouble(ruleValue);
            case "lt"         -> parseDouble(userValue) < parseDouble(ruleValue);
            case "gte"        -> parseDouble(userValue) >= parseDouble(ruleValue);
            case "lte"        -> parseDouble(userValue) <= parseDouble(ruleValue);
            default           -> false;
        };
    }

    private double parseDouble(String val) {
        try { return Double.parseDouble(val); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    // ── SSE Publishing ───────────────────────────────────────────────────────

    private void publishFlagChange(FeatureFlag flag, boolean deleted) {
        Map<String, Object> event = new HashMap<>();
        event.put("flagKey", flag.getFlagKey());
        event.put("environment", flag.getEnvironment());
        event.put("enabled", flag.getEnabled());
        event.put("value", flag.getStringValue());
        event.put("flagType", flag.getFlagType() != null ? flag.getFlagType() : "BOOLEAN");
        event.put("deleted", deleted);
        flagChangePublisher.publish(flag.getEnvironment(), "FLAG_CHANGED", event);
    }

    // ── Mappers ──────────────────────────────────────────────────────────────

    private FeatureFlagDTO toDTO(FeatureFlag flag) {
        FeatureFlagDTO dto = new FeatureFlagDTO();
        dto.setId(flag.getId());
        dto.setFlagKey(flag.getFlagKey());
        dto.setName(flag.getName());
        dto.setDescription(flag.getDescription());
        dto.setEnabled(flag.getEnabled());
        dto.setRolloutPercentage(flag.getRolloutPercentage());
        dto.setEnvironment(flag.getEnvironment());
        dto.setDefaultValue(flag.getDefaultValue());
        dto.setFlagType(flag.getFlagType() != null ? flag.getFlagType() : "BOOLEAN");
        dto.setStringValue(flag.getStringValue());
        dto.setTargetingRules(flag.getTargetingRules());
        dto.setCreatedBy(flag.getCreatedBy());
        dto.setCreatedAt(flag.getCreatedAt());
        dto.setUpdatedBy(flag.getUpdatedBy());
        dto.setUpdatedAt(flag.getUpdatedAt());
        dto.setVersion(flag.getVersion());
        return dto;
    }

    private String convertToJson(FeatureFlag flag) {
        try {
            return objectMapper.writeValueAsString(flag);
        } catch (JsonProcessingException e) {
            logger.error("Failed to serialize flag to JSON", e);
            return String.format("{\"id\":%d,\"flagKey\":\"%s\"}",
                flag.getId() != null ? flag.getId() : 0,
                flag.getFlagKey() != null ? flag.getFlagKey().replace("\"", "\\\"") : "");
        }
    }

    private void validateFlagDTO(FeatureFlagDTO dto) {
        if (dto.getRolloutPercentage() != null &&
            (dto.getRolloutPercentage() < MIN_ROLLOUT || dto.getRolloutPercentage() > MAX_ROLLOUT)) {
            throw new IllegalArgumentException(
                "Rollout percentage must be between " + MIN_ROLLOUT + " and " + MAX_ROLLOUT);
        }
    }
}
