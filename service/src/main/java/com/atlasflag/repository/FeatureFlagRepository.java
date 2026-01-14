package com.atlasflag.repository;

import com.atlasflag.domain.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, Long> {
    
    Optional<FeatureFlag> findByFlagKey(String flagKey);
    
    Optional<FeatureFlag> findByFlagKeyAndEnvironment(String flagKey, String environment);
    
    List<FeatureFlag> findByEnvironment(String environment);
    
    @Query("SELECT f FROM FeatureFlag f WHERE f.environment = :environment AND f.enabled = true")
    List<FeatureFlag> findEnabledFlagsByEnvironment(@Param("environment") String environment);
    
    boolean existsByFlagKey(String flagKey);
    
    boolean existsByFlagKeyAndEnvironment(String flagKey, String environment);
}
