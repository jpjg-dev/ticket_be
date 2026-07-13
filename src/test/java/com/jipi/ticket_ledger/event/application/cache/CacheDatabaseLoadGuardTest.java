package com.jipi.ticket_ledger.event.application.cache;

import com.jipi.ticket_ledger.global.exception.CacheTemporarilyUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CacheDatabaseLoadGuardTest {

    @Test
    @DisplayName("허용된 DB cache load가 모두 사용 중이면 추가 요청을 거절한다")
    void rejectsLoadWhenPermitsAreExhausted() throws Exception {
        EventCachePolicyProperties policy = policy(2);
        CacheDatabaseLoadGuard guard = new CacheDatabaseLoadGuard(
                policy, new EventCacheMetrics(new SimpleMeterRegistry()));
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> guard.execute(() -> await(entered, release)));
            Future<String> second = executor.submit(() -> guard.execute(() -> await(entered, release)));
            entered.await();

            assertThrows(CacheTemporarilyUnavailableException.class, () -> guard.execute(() -> "rejected"));

            release.countDown();
            first.get();
            second.get();
        }
    }

    private String await(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            release.await();
            return "loaded";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private EventCachePolicyProperties policy(int permits) {
        return new EventCachePolicyProperties(permits, Duration.ofMillis(300), Duration.ofMillis(20),
                Duration.ofSeconds(2), 1, 0.1, Duration.ofSeconds(1));
    }
}
