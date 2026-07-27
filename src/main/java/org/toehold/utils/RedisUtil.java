package org.toehold.utils;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis：LPUSH 入队（新在头）+ BLPOP 出队（先新后旧）；超限丢最旧。
 * 连不上按固定间隔一直重试。
 */
public class RedisUtil {
    private static final int SOCKET_TIMEOUT_MS = 5_000;
    private static final long WARN_LOG_INTERVAL_MS = 60_000;

    private static final Object POOL_LOCK = new Object();
    private static volatile JedisPool pool;
    private static final AtomicLong lastTrimWarnAt = new AtomicLong(0);

    private RedisUtil() {
    }

    /**
     * 入队：新消息在队头。超 maxLength 时裁掉队尾最旧数据。
     * Redis 不可用时按固定间隔一直重试，直到成功或线程中断。
     */
    public static void pushQueue(String queue, String message) {
        long intervalMs = reconnectIntervalMs();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                executeOnce(jedis -> {
                    jedis.lpush(queue, message);
                    trimOldest(jedis, queue);
                    return null;
                });
                return;
            } catch (Exception e) {
                Log.error("Redis pushQueue failed, retry in " + (intervalMs / 1000) + "s", e);
                resetPool();
                sleepQuietly(intervalMs);
            }
        }
    }

    /**
     * 阻塞弹出队头（最新）。超时返回 null。
     * Redis 不可用时按固定间隔一直重试。
     */
    public static String blockPopNewest(String queue, int timeoutSec) {
        long intervalMs = reconnectIntervalMs();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                return executeOnce(jedis -> {
                    List<String> result = jedis.blpop(Math.max(1, timeoutSec), queue);
                    if (result == null || result.size() < 2) {
                        return null;
                    }
                    return result.get(1);
                });
            } catch (Exception e) {
                Log.error("Redis blpop failed, retry in " + (intervalMs / 1000) + "s", e);
                resetPool();
                sleepQuietly(intervalMs);
            }
        }
        return null;
    }

    public static long queueLength(String queue) {
        try {
            Long len = executeOnce(jedis -> jedis.llen(queue));
            return len != null ? len : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void trimOldest(Jedis jedis, String queue) {
        int maxLength = AppConfig.redis().maxLength;
        if (maxLength <= 0) {
            return;
        }
        long len = jedis.llen(queue);
        if (len <= maxLength) {
            return;
        }
        // LPUSH 后新数据在 index 0；保留 [0, maxLength-1]，丢掉更旧的尾部
        jedis.ltrim(queue, 0, maxLength - 1);
        long now = System.currentTimeMillis();
        if (now - lastTrimWarnAt.get() >= WARN_LOG_INTERVAL_MS) {
            lastTrimWarnAt.set(now);
            Log.error("Redis queue [" + queue + "] trimmed oldest, kept " + maxLength
                    + " (was " + len + ")", null);
        }
    }

    private static <T> T executeOnce(RedisCallback<T> callback) {
        try (Jedis jedis = borrowConnection()) {
            return callback.run(jedis);
        }
    }

    private static Jedis borrowConnection() {
        Jedis jedis = pool().getResource();
        try {
            String pong = jedis.ping();
            if (!"PONG".equalsIgnoreCase(pong)) {
                throw new JedisConnectionException("Redis ping unexpected response: " + pong);
            }
        } catch (RuntimeException e) {
            jedis.close();
            throw e;
        }
        return jedis;
    }

    private static JedisPool pool() {
        JedisPool current = pool;
        if (current != null && !current.isClosed()) {
            return current;
        }
        synchronized (POOL_LOCK) {
            current = pool;
            if (current != null && !current.isClosed()) {
                return current;
            }
            if (current != null) {
                closePoolQuietly(current);
            }
            pool = createPool();
            Log.debug("Redis pool created: " + AppConfig.redis().host + ":" + AppConfig.redis().port);
            return pool;
        }
    }

    private static void resetPool() {
        synchronized (POOL_LOCK) {
            if (pool != null) {
                closePoolQuietly(pool);
                pool = null;
            }
        }
    }

    private static JedisPool createPool() {
        AppConfig.RedisConfig redis = AppConfig.redis();
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(32);
        config.setMaxIdle(16);
        config.setMinIdle(2);
        config.setTestOnBorrow(true);
        config.setTestWhileIdle(true);
        config.setTimeBetweenEvictionRunsMillis(30_000);

        String password = redis.password;
        if (password != null && !password.isEmpty()) {
            return new JedisPool(config, redis.host, redis.port, SOCKET_TIMEOUT_MS, password);
        }
        return new JedisPool(config, redis.host, redis.port, SOCKET_TIMEOUT_MS);
    }

    private static void closePoolQuietly(JedisPool p) {
        try {
            p.close();
        } catch (Exception ignored) {
        }
    }

    private static long reconnectIntervalMs() {
        return Math.max(1, AppConfig.redis().reconnectIntervalSec) * 1000L;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface RedisCallback<T> {
        T run(Jedis jedis);
    }
}
