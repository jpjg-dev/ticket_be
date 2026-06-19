package com.jipi.ticket_ledger.auth.domain;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByJtiAndUserId(String jti, Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshToken rt
            set rt.revokedAt = :now,
                rt.lastUsedAt = :now
            where rt.jti = :jti
              and rt.user.id = :userId
              and rt.tokenHash = :tokenHash
              and rt.revokedAt is null
              and rt.expiresAt > :now
            """)
    int consumeIfActive(
            @Param("jti") String jti,
            @Param("userId") Long userId,
            @Param("tokenHash") String tokenHash,
            @Param("now") Instant now
    );
}
