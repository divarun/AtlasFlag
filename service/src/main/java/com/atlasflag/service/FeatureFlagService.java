package com.atlasflag.service;

import com.atlasflag.domain.FeatureFlag;
import com.atlasflag.dto.FeatureFlagDTO;
import com.atlasflag.dto.FlagEvaluationRequest;
import com.atlasflag.dto.FlagEvaluationResponse;
import com.atlasflag.exception.ConflictException;
import com.atlasflag.repository.FeatureFlagRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class FeatureFlagService {
    
    private static final Logger logger = LoggerFactory.getLogger(FeatureFlagService.class);
    private static final int MIN_ROLLOUT_PERCENTAGE = 0;
    private static final int MAX_ROLLOUT_PERCENTAGE = 100;
    
    private final FeatureFlagRepository flagRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    
    public FeatureFlagService(FeatureFlagRepository flagRepository, AuditService auditService, ObjectMapper objectMapper) {
        this.flagRepository = flagRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }
    
    @Transactional
    @CacheEvict(value = "flags", key = "'flag:' + #dto.flagKey + ':env:' + (#dto.environment != null ? #dto.environment : 'default')")
    public FeatureFlagDTO createFlag(FeatureFlagDTO dto, String userId) {
        validateFlagDTO(dto);
        
        String environment = dto.getEnvironment() != null ? dto.getEnvironment() : "default";
        if (flagRepository.existsByFlagKeyAndEnvironment(dto.getFlagKey(), environment)) {
            throw new IllegalArgumentException("Flag with key '" + dto.getFlagKey() + 
                "' already exists in environment '" + environment + "'");
        }
        
        FeatureFlag flag = new FeatureFlag();
        flag.setFlagKey(dto.getFlagKey());
        flag.setName(dto.getName());
        flag.setDescription(dto.getDescription());
        flag.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : false);
        flag.setRolloutPercentage(dto.getRolloutPercentage());
        flag.setEnvironment(environment);
        flag.setDefaultValue(dto.getDefaultValue() != null ? dto.getDefaultValue() : false);
        flag.setCreatedBy(userId);
        
        FeatureFlag saved = flagRepository.save(flag);
        auditService.logAction("FeatureFlag", saved.getId(), "CREATE", userId, null, 
            convertToJson(saved));
        
        return toDTO(saved);
    }
    
    @Transactional
    @CachePut(value = "flags", key = "'flag:' + #result.flagKey + ':env:' + #result.environment")
    public FeatureFlagDTO updateFlag(Long id, FeatureFlagDTO dto, String userId) {
        validateFlagDTO(dto);
        
        FeatureFlag flag = flagRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Flag not found with id: " + id));
        
        String oldValue = convertToJson(flag);
        
        flag.setName(dto.getName());
        flag.setDescription(dto.getDescription());
        flag.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : flag.getEnabled());
        flag.setRolloutPercentage(dto.getRolloutPercentage());
        flag.setDefaultValue(dto.getDefaultValue() != null ? dto.getDefaultValue() : flag.getDefaultValue());
        flag.setUpdatedBy(userId);
        
        try {
            FeatureFlag saved = flagRepository.save(flag);
            auditService.logAction("FeatureFlag", saved.getId(), "UPDATE", userId, oldValue, 
                convertToJson(saved));
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
            auditService.logAction("FeatureFlag", saved.getId(), 
                saved.getEnabled() ? "ENABLE" : "DISABLE", userId, oldValue, convertToJson(saved));
            return toDTO(saved);
        } catch (OptimisticLockingFailureException e) {
            throw new ConflictException("Flag was modified by another user. Please refresh and try again.");
        }
    }
    
    @Cacheable(value = "flags", key = "'flag:' + #flagKey + ':env:' + #environment")
    public Optional<FeatureFlag> getFlag(String flagKey, String environment) {
        return flagRepository.findByFlagKeyAndEnvironment(flagKey, environment);
    }
    
    public List<FeatureFlagDTO> getAllFlags(String environment) {
        return flagRepository.findByEnvironment(environment).stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }
    
    public Optional<FeatureFlagDTO> getFlagById(Long id) {
        return flagRepository.findById(id)
            .map(this::toDTO);
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
        
        // If flag is disabled, return default value
        if (!flag.getEnabled()) {
            response.setEnabled(flag.getDefaultValue());
            response.setReason("FLAG_DISABLED");
            return response;
        }
        
        // If rollout percentage is set, calculate based on userId hash
        if (flag.getRolloutPercentage() != null && request.getUserId() != null) {
            // Fix: Use bitwise AND to avoid Math.abs(Integer.MIN_VALUE) issue
            int hash = (request.getUserId().hashCode() & 0x7FFFFFFF) % 100;
            boolean inRollout = hash < flag.getRolloutPercentage();
            response.setEnabled(inRollout);
            response.setReason(inRollout ? "ROLLOUT_PERCENTAGE" : "ROLLOUT_EXCLUDED");
            return response;
        }
        
        // Flag is enabled and no rollout percentage
        response.setEnabled(true);
        response.setReason("FLAG_ENABLED");
        return response;
    }
    
    @Transactional
    public void deleteFlag(Long id, String userId) {
        FeatureFlag flag = flagRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Flag not found with id: " + id));
        
        String flagKey = flag.getFlagKey();
        String environment = flag.getEnvironment();
        String oldValue = convertToJson(flag);
        
        flagRepository.delete(flag);
        auditService.logAction("FeatureFlag", id, "DELETE", userId, oldValue, null);
        
        // Evict specific cache entry after deletion
        evictCacheEntry(flagKey, environment);
    }
    
    private void evictCacheEntry(String flagKey, String environment) {
        // This will be handled by CacheManager if needed, or use CacheEvict programmatically
        logger.debug("Cache entry should be evicted for flag: {} in environment: {}", flagKey, environment);
    }
    
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
            // Fallback to safe representation
            return String.format("{\"id\":%d,\"flagKey\":\"%s\"}", 
                flag.getId() != null ? flag.getId() : 0, 
                flag.getFlagKey() != null ? flag.getFlagKey().replace("\"", "\\\"") : "");
        }
    }
    
    private void validateFlagDTO(FeatureFlagDTO dto) {
        if (dto.getRolloutPercentage() != null) {
            if (dto.getRolloutPercentage() < MIN_ROLLOUT_PERCENTAGE || 
                dto.getRolloutPercentage() > MAX_ROLLOUT_PERCENTAGE) {
                throw new IllegalArgumentException(
                    String.format("Rollout percentage must be between %d and %d", 
                        MIN_ROLLOUT_PERCENTAGE, MAX_ROLLOUT_PERCENTAGE));
            }
        }
    }
}
