package com.atlasflag.repository;

import com.atlasflag.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    
    Page<AuditLog> findByEntityTypeAndEntityId(String entityType, Long entityId, Pageable pageable);
    
    Page<AuditLog> findByUserId(String userId, Pageable pageable);
    
    @Query("SELECT a FROM AuditLog a WHERE a.timestamp >= :from AND a.timestamp <= :to")
    Page<AuditLog> findByTimestampBetween(@Param("from") Instant from, @Param("to") Instant to, Pageable pageable);
    
    List<AuditLog> findByEntityTypeAndEntityIdOrderByTimestampDesc(String entityType, Long entityId);
}
