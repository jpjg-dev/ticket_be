package com.jipi.ticket_ledger.auth.domain;

import com.jipi.ticket_ledger.user.domain.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(
        name = "refresh_tokens",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_refresh_token_jti", columnNames = "jti"),
                @UniqueConstraint(name = "uk_refresh_token_hash", columnNames = "token_hash")
        }
)
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", nullable = false, length = 255)
    private String tokenHash;

    @Column(nullable = false, length = 100)
    private String jti;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    public RefreshToken(User user, String tokenHash, String jti, Instant expiresAt, Instant createdAt) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.jti = jti;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public RefreshToken(User user, String tokenHash, String jti, LocalDateTime expiresAt, LocalDateTime createdAt) {
        this(
                user,
                tokenHash,
                jti,
                expiresAt.atZone(ZoneId.systemDefault()).toInstant(),
                createdAt.atZone(ZoneId.systemDefault()).toInstant()
        );
    }


    public void revoke(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public void revoke(LocalDateTime revokedAt) {
        revoke(revokedAt.atZone(ZoneId.systemDefault()).toInstant());
    }

    public boolean isRevoked() {
        return this.revokedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !this.expiresAt.isAfter(now);
    }
}
