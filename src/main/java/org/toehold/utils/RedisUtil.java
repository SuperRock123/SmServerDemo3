package org.toehold.utils;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

public class RedisUtil {
    private static JedisPool pool = new JedisPool("localhost", 6379);

    public static void publish(String channel, String message) {
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channel, message);
        }
    }

    public static void subscribe(String channel, redis.clients.jedis.JedisPubSub listener) {
        new Thread(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.subscribe(listener, channel);
            }
        }).start();
    }

    public static void pushQueue(String queue, String message) {
        try (Jedis jedis = pool.getResource()) {
            jedis.lpush(queue, message);
        }
    }

    public static String popQueue(String queue) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.rpop(queue);
        }
    }
}
