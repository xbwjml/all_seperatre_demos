package com.example.demo.single.rateLimiter;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

public class RateLimiterCluster {

    private static final long WINDOW_SECONDS = 60;
    private static final int MAX_REQUESTS = 3;
    private static final String KEY_PREFIX = "rate_limiter:";

    /**
     * Lua 脚本保证 "清理 + 计数 + 写入" 在 Redis 侧原子执行。
     *
     * KEYS[1] = rate_limiter:{uid}
     * ARGV[1] = 窗口起始时间 (timestamp - window)
     * ARGV[2] = 当前时间戳 (score & member 唯一后缀)
     * ARGV[3] = 最大允许请求数
     * ARGV[4] = key 过期时间（秒）
     */
    private static final String LUA_SCRIPT = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            local count = redis.call('ZCARD', KEYS[1])
            if count < tonumber(ARGV[3]) then
                redis.call('ZADD', KEYS[1], ARGV[2], ARGV[2] .. ':' .. count)
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4]))
                return 1
            end
            return 0
            """;

    private static final RedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(LUA_SCRIPT, Long.class);

    private final StringRedisTemplate redisTemplate;

    public RateLimiterCluster(StringRedisTemplate redisTemplate) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate must not be null");
    }

    public boolean allow(String uid, long timestamp) {
        Objects.requireNonNull(uid, "uid must not be null");
        if (timestamp < 0) {
            throw new IllegalArgumentException("timestamp must not be negative");
        }

        List<String> keys = Collections.singletonList(KEY_PREFIX + uid);
        String windowStart = String.valueOf(timestamp - WINDOW_SECONDS);
        String now = String.valueOf(timestamp);
        String maxRequests = String.valueOf(MAX_REQUESTS);
        String expireSeconds = String.valueOf(WINDOW_SECONDS + 1);

        Long result = redisTemplate.execute(SCRIPT, keys, windowStart, now, maxRequests, expireSeconds);
        return result != null && result == 1L;
    }
}
