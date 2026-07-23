package com.jipi.ticket_ledger.featureflag.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface QueueFeatureFlagRepository extends JpaRepository<QueueFeatureFlag, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update QueueFeatureFlag featureFlag
               set featureFlag.queueMode = :queueMode,
                   featureFlag.updatedAt = :updatedAt,
                   featureFlag.version = featureFlag.version + 1
             where featureFlag.id = :id
               and featureFlag.version = :expectedVersion
            """)
    int updateModeIfVersionMatches(
            @Param("id") long id,
            @Param("queueMode") QueueMode queueMode,
            @Param("updatedAt") Instant updatedAt,
            @Param("expectedVersion") long expectedVersion
    );
}
