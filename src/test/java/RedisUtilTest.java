//
//import org.junit.jupiter.api.AfterEach;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.toehold.utils.RedisUtil;
//import redis.clients.jedis.Jedis;
//import redis.clients.jedis.JedisPool;
//import redis.clients.jedis.JedisPubSub;
//
//import java.util.concurrent.CountDownLatch;
//import java.util.concurrent.TimeUnit;
//
//import static org.junit.jupiter.api.Assertions.*;
//
//public class RedisUtilTest {
//
//    private Jedis jedis;
//
//    @BeforeEach
//    void setUp() {
//        // 初始化 jedis 连接用于测试验证
//        jedis = new Jedis("localhost", 6379);
//        jedis.flushAll(); // 清空所有数据以确保测试独立性
//    }
//
//    @AfterEach
//    void tearDown() {
//        if (jedis != null) {
//            jedis.close();
//        }
//    }
//
//    @Test
//    void testPublishAndSubscribe() throws InterruptedException {
//        String channel = "test_channel";
//        String message = "Hello Redis";
//
//        // 使用 CountDownLatch 等待异步消息接收
//        CountDownLatch latch = new CountDownLatch(1);
//        StringBuilder receivedMessage = new StringBuilder();
//
//        // 创建订阅者
//        JedisPubSub listener = new JedisPubSub() {
//            @Override
//            public void onMessage(String ch, String msg) {
//                if (channel.equals(ch)) {
//                    receivedMessage.append(msg);
//                    latch.countDown();
//                }
//            }
//        };
//
//        // 订阅频道
//        RedisUtil.subscribe(channel, listener);
//
//        // 等待订阅建立
//        Thread.sleep(100);
//
//        // 发布消息
//        RedisUtil.publish(channel, message);
//
//        // 等待消息接收或超时
//        boolean received = latch.await(5, TimeUnit.SECONDS);
//
//        assertTrue(received, "应该接收到消息");
//        assertEquals(message, receivedMessage.toString(), "接收到的消息应该与发送的消息一致");
//    }
//
//    @Test
//    void testPushAndPopQueue() {
//        String queueName = "test_queue";
//        String message1 = "First message";
//        String message2 = "Second message";
//
//        // 推入消息
//        RedisUtil.pushQueue(queueName, message1);
//        RedisUtil.pushQueue(queueName, message2);
//
//        // 弹出消息并验证顺序（后进先出）
//        String poppedMessage1 = RedisUtil.popQueue(queueName);
//        String poppedMessage2 = RedisUtil.popQueue(queueName);
//
//        assertEquals(message1, poppedMessage1, "应该按照先进后出的顺序弹出消息");
//        assertEquals(message2, poppedMessage2, "应该按照先进后出的顺序弹出消息");
//
//        // 验证队列为空
//        String emptyPop = RedisUtil.popQueue(queueName);
//        assertNull(emptyPop, "当队列为空时应返回null");
//    }
//
//    @Test
//    void testMultiplePushAndPop() {
//        String queueName = "multi_test_queue";
//        String[] messages = {"msg1", "msg2", "msg3", "msg4", "msg5"};
//
//        // 批量推入消息
//        for (String message : messages) {
//            RedisUtil.pushQueue(queueName, message);
//        }
//
//        // 验证队列长度
//        Long queueLength = jedis.llen(queueName);
//        assertEquals(messages.length, queueLength.intValue(), "队列长度应该等于推入的消息数量");
//
//        // 按照先进先出顺序弹出消息
//        for (int i = 0; i < messages.length; i++) {
//            String popped = RedisUtil.popQueue(queueName);
//            assertEquals(messages[i], popped, "弹出的消息应该按照先进先出的顺序");
//        }
//    }
//}
