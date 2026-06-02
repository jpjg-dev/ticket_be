package com.jipi.ticket_ledger.auth.application;

import com.jipi.ticket_ledger.auth.domain.RefreshToken;
import com.jipi.ticket_ledger.auth.domain.RefreshTokenRepository;
import com.jipi.ticket_ledger.auth.infrastructure.JwtTokenProvider;
import com.jipi.ticket_ledger.auth.infrastructure.TokenHasher;
import com.jipi.ticket_ledger.global.exception.AuthUnauthorizedException;
import com.jipi.ticket_ledger.user.domain.User;
import com.jipi.ticket_ledger.user.domain.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.jpa.show-sql=false",
        "logging.level.org.hibernate.SQL=OFF",
        "logging.level.org.hibernate.orm.jdbc.bind=OFF"
})
class AuthServiceConcurrencyTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private TokenHasher tokenHasher;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("reissue: 동일 Refresh Token 동시 요청은 하나만 성공한다")
    void reissueConcurrentSameRefreshTokenSucceedsOnce() throws InterruptedException {
        String runId = String.valueOf(System.nanoTime());
        LocalDateTime now = LocalDateTime.now();
        User user = userRepository.save(new User(
                "refresh-concurrency-" + runId + "@test.com",
                "password",
                "RT 동시성 테스터",
                now
        ));
        String jti = "refresh-concurrency-" + runId;
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId(), jti);
        refreshTokenRepository.save(new RefreshToken(
                user,
                tokenHasher.hash(refreshToken),
                jti,
                jwtTokenProvider.getExpirationAsLocalDateTime(refreshToken),
                now
        ));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger unauthorizedCount = new AtomicInteger();

        for (int index = 0; index < 2; index++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    authService.reissue(refreshToken);
                    successCount.incrementAndGet();
                } catch (AuthUnauthorizedException exception) {
                    unauthorizedCount.incrementAndGet();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        assertTrue(readyLatch.await(10, TimeUnit.SECONDS));
        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        long savedTokenCount = refreshTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .count();
        long activeTokenCount = refreshTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .filter(token -> !token.isRevoked())
                .count();

        assertEquals(1, successCount.get());
        assertEquals(1, unauthorizedCount.get());
        assertEquals(2, savedTokenCount);
        assertEquals(1, activeTokenCount);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            refreshTokenRepository.findAll().stream()
                    .filter(token -> token.getUser().getId().equals(user.getId()))
                    .map(RefreshToken::getId)
                    .forEach(refreshTokenRepository::deleteById);
            userRepository.deleteById(user.getId());
        });
    }

    @Test
    @DisplayName("reissue: 같은 사용자의 서로 다른 Refresh Token은 각각 재발급할 수 있다")
    void reissueDifferentRefreshTokensForSameUserBothSucceed() {
        String runId = String.valueOf(System.nanoTime());
        LocalDateTime now = LocalDateTime.now();
        User user = userRepository.save(new User(
                "refresh-multi-login-" + runId + "@test.com",
                "password",
                "RT 다중 로그인 테스터",
                now
        ));
        String firstRefreshToken = saveRefreshToken(user, "refresh-first-" + runId, now);
        String secondRefreshToken = saveRefreshToken(user, "refresh-second-" + runId, now);

        authService.reissue(firstRefreshToken);
        authService.reissue(secondRefreshToken);

        long savedTokenCount = countTokens(user);
        long activeTokenCount = countActiveTokens(user);

        assertEquals(4, savedTokenCount);
        assertEquals(2, activeTokenCount);

        deleteUserAndTokens(user);
    }

    private String saveRefreshToken(User user, String jti, LocalDateTime now) {
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId(), jti);
        refreshTokenRepository.save(new RefreshToken(
                user,
                tokenHasher.hash(refreshToken),
                jti,
                jwtTokenProvider.getExpirationAsLocalDateTime(refreshToken),
                now
        ));
        return refreshToken;
    }

    private long countTokens(User user) {
        return refreshTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .count();
    }

    private long countActiveTokens(User user) {
        return refreshTokenRepository.findAll().stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .filter(token -> !token.isRevoked())
                .count();
    }

    private void deleteUserAndTokens(User user) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            refreshTokenRepository.findAll().stream()
                    .filter(token -> token.getUser().getId().equals(user.getId()))
                    .map(RefreshToken::getId)
                    .forEach(refreshTokenRepository::deleteById);
            userRepository.deleteById(user.getId());
        });
    }
}
