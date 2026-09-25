package com.toys.video.common.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeTest {

    private static final int THREADS = 8;
    private static final int IDS_PER_THREAD = 10_000;

    @Test
    void nextId_isThreadSafeAndUniqueUnderConcurrency() throws Exception {
        Snowflake snowflake = new Snowflake(1, 1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> futures = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            futures.add(pool.submit(() -> {
                List<Long> ids = new ArrayList<>(IDS_PER_THREAD);
                start.await();
                for (int i = 0; i < IDS_PER_THREAD; i++) {
                    ids.add(snowflake.nextId());
                }
                return ids;
            }));
        }
        start.countDown();

        Set<Long> all = new HashSet<>();
        for (Future<List<Long>> future : futures) {
            all.addAll(future.get());
        }
        pool.shutdown();

        assertEquals((long) THREADS * IDS_PER_THREAD, all.size(), "并发生成的 id 不得重复");
    }

    @Test
    void nextId_isStrictlyIncreasingForConsecutiveCalls() {
        Snowflake snowflake = new Snowflake(0, 0);
        long prev = snowflake.nextId();
        for (int i = 0; i < 1000; i++) {
            long next = snowflake.nextId();
            assertTrue(next > prev, "相邻两次调用必须递增: " + prev + " -> " + next);
            prev = next;
        }
    }
}
