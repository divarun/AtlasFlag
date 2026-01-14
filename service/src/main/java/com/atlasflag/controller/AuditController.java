package com.atlasflag.controller;

import com.atlasflag.domain.AuditLog;
import com.atlasflag.service.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {
    
    private final AuditService auditService;
    
    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }
    
    @GetMapping("/entity/{entityType}/{entityId}")
    public ResponseEntity<Page<AuditLog>> getAuditLogs(
            @PathVariable String entityType,
            @PathVariable Long entityId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditLog> logs = auditService.getAuditLogs(entityType, entityId, pageable);
        return ResponseEntity.ok(logs);
    }
    
    @GetMapping("/user/{userId}")
    public ResponseEntity<Page<AuditLog>> getAuditLogsByUser(
            @PathVariable String userId,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<AuditLog> logs = auditService.getAuditLogsByUser(userId, pageable);
        return ResponseEntity.ok(logs);
    }
}
