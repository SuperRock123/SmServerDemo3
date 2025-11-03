package org.toehold.utils;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Redis工具类，提供发布订阅和队列操作功能
 */
public class RedisUtil {
    private static JedisPool pool = new JedisPool(new JedisPoolConfig(), AppConfig.redis().host, AppConfig.redis().port, 2000, AppConfig.redis().password);

    /**
     * 向指定频道发布消息
     *
     * @param channel 频道名称
     * @param message 要发布的消息内容
     */
    public static void publish(String channel, String message) {
        try (Jedis jedis = pool.getResource()) {
            jedis.publish(channel, message);
        }
    }

    /**
     * 订阅指定频道的消息
     *
     * @param channel  要订阅的频道名称
     * @param listener 消息监听器
     */
    public static void subscribe(String channel, redis.clients.jedis.JedisPubSub listener) {
        // 在新线程中执行订阅操作，避免阻塞主线程
        new Thread(() -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.subscribe(listener, channel);
            }
        }).start();
    }

    /**
     * 向队列左侧推送消息
     *
     * @param queue   队列名称
     * @param message 要推送的消息内容
     */
    public static void pushQueue(String queue, String message) {
        try (Jedis jedis = pool.getResource()) {
            jedis.lpush(queue, message);
        }
    }

    /**
     * 从队列右侧弹出消息
     *
     * @param queue 队列名称
     * @return 弹出的消息内容，如果队列为空则返回null
     */
    public static String popQueue(String queue) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.rpop(queue);
        }
    }
}
