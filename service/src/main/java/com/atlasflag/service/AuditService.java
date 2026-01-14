package com.atlasflag.service;

import com.atlasflag.domain.AuditLog;
import com.atlasflag.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class AuditService {
    
    private static final Logger auditFailureLogger = LoggerFactory.getLogger("audit.failure");
    
    private final AuditLogRepository auditLogRepository;
    
    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }
    
    /**
     * Log audit action asynchronously.
     * Note: @Async methods should not use @Transactional as the transaction
     * may not be committed when the async method executes.
     */
    @Async
    public void logAction(String entityType, Long entityId, String action, String userId, 
                         String oldValue, String newValue) {
        try {
            AuditLog log = new AuditLog();
            log.setEntityType(entityType);
            log.setEntityId(entityId);
            log.setAction(action);
            log.setUserId(userId);
            log.setTimestamp(Instant.now());
            
            if (oldValue != null || newValue != null) {
                String changes = String.format("{\"old\":%s,\"new\":%s}", 
                    oldValue != null ? oldValue : "null",
                    newValue != null ? newValue : "null");
                log.setChanges(changes);
            }
            
            auditLogRepository.save(log);
        } catch (Exception e) {
            // Log audit failures to separate logger for monitoring
            auditFailureLogger.error("Failed to audit action: entityType={}, entityId={}, action={}, userId={}", 
                entityType, entityId, action, userId, e);
        }
    }
    
    @Async
    public void logAction(String entityType, Long entityId, String action, String userId,
                         String oldValue, String newValue, HttpServletRequest request) {
        try {
            AuditLog log = new AuditLog();
            log.setEntityType(entityType);
            log.setEntityId(entityId);
            log.setAction(action);
            log.setUserId(userId);
            log.setTimestamp(Instant.now());
            log.setIpAddress(getClientIpAddress(request));
            
            if (oldValue != null || newValue != null) {
                String changes = String.format("{\"old\":%s,\"new\":%s}", 
                    oldValue != null ? oldValue : "null",
                    newValue != null ? newValue : "null");
                log.setChanges(changes);
            }
            
            auditLogRepository.save(log);
        } catch (Exception e) {
            auditFailureLogger.error("Failed to audit action: entityType={}, entityId={}, action={}, userId={}", 
                entityType, entityId, action, userId, e);
        }
    }
    
    public Page<AuditLog> getAuditLogs(String entityType, Long entityId, Pageable pageable) {
        return auditLogRepository.findByEntityTypeAndEntityId(entityType, entityId, pageable);
    }
    
    public Page<AuditLog> getAuditLogsByUser(String userId, Pageable pageable) {
        return auditLogRepository.findByUserId(userId, pageable);
    }
    
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
