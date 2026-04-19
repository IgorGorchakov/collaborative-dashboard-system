package com.example.dashboard.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class UserServiceTest {

    private final UserService registry = new UserService();

    @Test
    void acceptsFirstNameAndRejectsDuplicateOnSameDashboard() {
        UUID board = UUID.randomUUID();

        assertThat(registry.tryJoin(board, "s1", "alice")).isTrue();
        assertThat(registry.tryJoin(board, "s2", "alice")).isFalse();
        assertThat(registry.usersOf(board)).containsExactly("alice");
    }

    @Test
    void allowsSameNameOnDifferentDashboards() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        assertThat(registry.tryJoin(a, "s1", "alice")).isTrue();
        assertThat(registry.tryJoin(b, "s2", "alice")).isTrue();
        assertThat(registry.usersOf(a)).containsExactly("alice");
        assertThat(registry.usersOf(b)).containsExactly("alice");
    }

    @Test
    void leaveFreesNameAndReturnsEntry() {
        UUID board = UUID.randomUUID();
        registry.tryJoin(board, "s1", "alice");

        Optional<UserService.Leave> leave = registry.leave("s1");

        assertThat(leave).isPresent();
        assertThat(leave.get().dashboardId()).isEqualTo(board);
        assertThat(leave.get().username()).isEqualTo("alice");
        assertThat(registry.usersOf(board)).isEmpty();
        // name is now free
        assertThat(registry.tryJoin(board, "s2", "alice")).isTrue();
    }

    @Test
    void leaveForUnknownSessionReturnsEmpty() {
        assertThat(registry.leave("never-connected")).isEmpty();
    }

    @Test
    void usersOfReturnsCaseInsensitiveSortedSnapshot() {
        UUID board = UUID.randomUUID();
        registry.tryJoin(board, "s1", "carol");
        registry.tryJoin(board, "s2", "Alice");
        registry.tryJoin(board, "s3", "bob");

        assertThat(registry.usersOf(board)).containsExactly("Alice", "bob", "carol");
    }

    @Test
    void concurrentJoinsForSameNameProduceExactlyOneWinner() throws Exception {
        UUID board = UUID.randomUUID();
        int threadCount = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger(0);

        try {
            for (int i = 0; i < threadCount; i++) {
                String sessionId = "s" + i;
                pool.submit(() -> {
                    start.await();
                    if (registry.tryJoin(board, sessionId, "contested")) {
                        winners.incrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (!pool.isTerminated()) {
                pool.shutdownNow();
            }
        }

        assertThat(winners.get()).isEqualTo(1);
        assertThat(registry.usersOf(board)).isEqualTo(List.of("contested"));
    }
}
