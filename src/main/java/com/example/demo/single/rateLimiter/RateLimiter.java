package com.example.demo.single.rateLimiter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RateLimiter {

    private static final long WINDOW_SECONDS = 60;
    private static final int MAX_REQUESTS = 3;
    private static final long CLEANUP_INTERVAL_SECONDS = 120;

    private final Map<String, Deque<Long>> requestLog = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    public RateLimiter() {
        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limiter-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::evictStaleEntries,
                CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public boolean allow(String uid, long timestamp) {
        Objects.requireNonNull(uid, "uid must not be null");
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }

        Deque<Long> timestamps = requestLog.computeIfAbsent(uid, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            if (!timestamps.isEmpty() && timestamp < timestamps.peekLast()) {
                throw new IllegalArgumentException(
                        "timestamp must not be earlier than the last recorded request");
            }

            // 闭区间 [timestamp - WINDOW_SECONDS, timestamp]
            while (!timestamps.isEmpty() && timestamps.peekFirst() < timestamp - WINDOW_SECONDS) {
                timestamps.pollFirst();
            }

            if (timestamps.size() < MAX_REQUESTS) {
                timestamps.addLast(timestamp);
                return true;
            }

            return false;
        }
    }

    private void evictStaleEntries() {
        Iterator<Map.Entry<String, Deque<Long>>> it = requestLog.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> timestamps = it.next().getValue();
            synchronized (timestamps) {
                if (timestamps.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    public void shutdown() {
        cleaner.shutdown();
    }
}
