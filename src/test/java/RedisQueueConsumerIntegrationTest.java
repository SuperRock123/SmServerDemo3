//
//import cn.zmvision.ccm.smserver.entitys.SensorData;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
//import org.eclipse.paho.client.mqttv3.*;
//import org.junit.jupiter.api.*;
//import org.toehold.RedisQueueConsumer;
//import org.toehold.utils.RedisUtil;
//import redis.clients.jedis.Jedis;
//
//import java.time.LocalDateTime;
//import java.util.ArrayList;
//import java.util.UUID;
//import java.util.concurrent.CountDownLatch;
//import java.util.concurrent.TimeUnit;
//
//import static org.junit.jupiter.api.Assertions.*;
//
//public class RedisQueueConsumerIntegrationTest {
//
//    private Jedis jedis;
//    private static final String TEST_QUEUE = "sensor_queue";
//    private ObjectMapper testMapper;
//    private MqttClient mqttClient;
//
//    @BeforeEach
//    void setUp() throws MqttException {
//        jedis = new Jedis("localhost", 6379);
//        jedis.flushAll(); // 清空Redis数据确保测试独立性
//
//        // 创建支持 Java 8 时间类型的 ObjectMapper 用于测试验证
//        testMapper = new ObjectMapper();
//        testMapper.registerModule(new JavaTimeModule());
//
//        // 初始化MQTT客户端用于测试
//        mqttClient = new MqttClient("tcp://localhost:1883", MqttClient.generateClientId());
//        MqttConnectOptions options = new MqttConnectOptions();
//        options.setCleanSession(true);
//        mqttClient.connect(options);
//    }
//
//    @AfterEach
//    void tearDown() {
//        if (jedis != null) {
//            jedis.close();
//        }
//
//        if (mqttClient != null && mqttClient.isConnected()) {
//            try {
//                mqttClient.disconnect();
//                mqttClient.close();
//            } catch (MqttException e) {
//                // 忽略
//            }
//        }
//    }
//
//    @Test
//    void testConsumerIntegrationWithRealMqtt() throws Exception {
//        // 集成测试：手动放入消息，启动RedisQueueConsumer，验证消费者是否能解析并发送到MQTT
//
//        // 1. 手动创建并放入几个测试消息到Redis队列
//        int messageCount = 3;
//        SensorData[] testDatas = new SensorData[messageCount];
//
//        for (int i = 0; i < messageCount; i++) {
//            SensorData sensorData = createTestSensorData();
//            sensorData.setSn("TEST_SN_" + i);
//            sensorData.setReserve("Integration test data " + i);
//
//            // 为偶数索引的数据添加图片数据，以测试不同的MQTT主题
//            if (i % 2 == 0) {
//                sensorData.setPicData(createTestImageData());
//            } else {
//                sensorData.setPicData(null);
//            }
//
//            testDatas[i] = sensorData;
//
//            // 将数据序列化并推送到Redis队列
//            String jsonData = testMapper.writeValueAsString(sensorData);
//            RedisUtil.pushQueue(TEST_QUEUE, jsonData);
//        }
//
//        // 2. 验证数据已成功放入队列
//        Long queueLength = jedis.llen(TEST_QUEUE);
//        assertEquals(messageCount, queueLength.intValue(), "队列应该包含所有测试数据");
//
//        // 3. 启动RedisQueueConsumer
//        RedisQueueConsumer consumer = new RedisQueueConsumer();
//        Thread consumerThread = new Thread(consumer, "RedisQueueConsumer-Test");
//        consumerThread.setDaemon(true);
//        consumerThread.start();
//
//        // 4. 等待一段时间让消费者处理所有消息
//        Thread.sleep(3000);
//
//        // 5. 验证队列是否已清空
//        queueLength = jedis.llen(TEST_QUEUE);
//        assertEquals(0, queueLength.intValue(), "队列应该已被清空");
//
//        // 6. 中断消费者线程
//        consumerThread.interrupt();
//
//        // 等待线程结束
//        try {
//            consumerThread.join(1000);
//        } catch (InterruptedException e) {
//            // 忽略
//        }
//    }
//
//    @Test
//    void testConsumerHandlesMessagesInQueue() throws Exception {
//        // 测试消费者处理队列中的消息（不实际连接MQTT）
//
//        // 1. 手动放入测试消息
//        SensorData sensorData1 = createTestSensorData();
//        sensorData1.setSn("SN_WITH_IMAGE");
//        sensorData1.setPicData(createTestImageData()); // 包含图片数据
//
//        SensorData sensorData2 = createTestSensorData();
//        sensorData2.setSn("SN_WITHOUT_IMAGE");
//        sensorData2.setPicData(null); // 不包含图片数据
//
//        String jsonData1 = testMapper.writeValueAsString(sensorData1);
//        String jsonData2 = testMapper.writeValueAsString(sensorData2);
//
//        RedisUtil.pushQueue(TEST_QUEUE, jsonData1);
//        RedisUtil.pushQueue(TEST_QUEUE, jsonData2);
//
//        // 2. 验证数据已放入队列
//        Long queueLength = jedis.llen(TEST_QUEUE);
//        assertEquals(2, queueLength.intValue(), "队列应该包含2条测试数据");
//
//        // 3. 模拟消费者处理逻辑（不实际启动消费者线程）
//        // 这里我们直接测试RedisUtil.popQueue和数据解析
//        String msg1 = RedisUtil.popQueue(TEST_QUEUE);
//        String msg2 = RedisUtil.popQueue(TEST_QUEUE);
//
//        assertNotNull(msg1, "应该能从队列中获取第一条消息");
//        assertNotNull(msg2, "应该能从队列中获取第二条消息");
//
//        // 4. 验证消息可以被正确解析
//        ObjectMapper consumerMapper = new ObjectMapper();
//        consumerMapper.registerModule(new JavaTimeModule());
//
//        SensorData parsedData1 = consumerMapper.readValue(msg1, SensorData.class);
//        SensorData parsedData2 = consumerMapper.readValue(msg2, SensorData.class);
//
//        assertEquals("SN_WITH_IMAGE", parsedData1.getSn());
//        assertNotNull(parsedData1.getPicData(), "第一条数据应该包含图片");
//
//        assertEquals("SN_WITHOUT_IMAGE", parsedData2.getSn());
//        assertNull(parsedData2.getPicData(), "第二条数据不应该包含图片");
//    }
//
//    @Test
//    void testConsumerHandlesInvalidJsonInQueue() throws Exception {
//        // 测试消费者处理队列中的无效JSON数据
//
//        // 1. 放入有效的JSON数据
//        SensorData sensorData = createTestSensorData();
//        String validJson = testMapper.writeValueAsString(sensorData);
//        RedisUtil.pushQueue(TEST_QUEUE, validJson);
//
//        // 2. 放入无效的JSON数据
//        String invalidJson = "{ invalid json ";
//        RedisUtil.pushQueue(TEST_QUEUE, invalidJson);
//
//        // 3. 再放入一条有效数据
//        SensorData sensorData2 = createTestSensorData();
//        sensorData2.setSn("SECOND_VALID");
//        String validJson2 = testMapper.writeValueAsString(sensorData2);
//        RedisUtil.pushQueue(TEST_QUEUE, validJson2);
//
//        // 4. 验证队列中有3条数据
//        Long queueLength = jedis.llen(TEST_QUEUE);
//        assertEquals(3, queueLength.intValue(), "队列应该包含3条数据");
//
//        // 5. 模拟消费者处理逻辑
//        String msg1 = RedisUtil.popQueue(TEST_QUEUE); // 应该是有效的JSON
//        String msg2 = RedisUtil.popQueue(TEST_QUEUE); // 应该是无效的JSON
//        String msg3 = RedisUtil.popQueue(TEST_QUEUE); // 应该是有效的JSON
//
//        // 6. 验证数据可以被正确处理
//        ObjectMapper consumerMapper = new ObjectMapper();
//        consumerMapper.registerModule(new JavaTimeModule());
//
//        // 第一条消息应该能被解析
//        assertDoesNotThrow(() -> {
//            consumerMapper.readValue(msg1, SensorData.class);
//        }, "第一条消息应该是有效的JSON");
//
//        // 第二条消息应该无法被解析
//        assertThrows(Exception.class, () -> {
//            consumerMapper.readValue(msg2, SensorData.class);
//        }, "第二条消息应该是无效的JSON");
//
//        // 第三条消息应该能被解析
//        assertDoesNotThrow(() -> {
//            SensorData data = consumerMapper.readValue(msg3, SensorData.class);
//            assertEquals("SECOND_VALID", data.getSn());
//        }, "第三条消息应该是有效的JSON");
//    }
//
//    @Test
//    void testConsumerMqttTopicSelection() throws Exception {
//        // 测试消费者根据数据内容选择正确的MQTT主题
//
//        // 使用CountDownLatch等待MQTT消息接收
//        CountDownLatch allDataLatch = new CountDownLatch(1);
//        CountDownLatch resDataLatch = new CountDownLatch(1);
//
//        // 订阅MQTT主题以验证消息发布
//        mqttClient.setCallback(new MqttCallback() {
//            @Override
//            public void connectionLost(Throwable cause) {}
//
//            @Override
//            public void messageArrived(String topic, MqttMessage message) throws Exception {
//                System.out.println("Received message on topic: " + topic);
//                if ("sensor/all_data".equals(topic)) {
//                    allDataLatch.countDown();
//                } else if ("sensor/res_data".equals(topic)) {
//                    resDataLatch.countDown();
//                }
//            }
//
//            @Override
//            public void deliveryComplete(IMqttDeliveryToken token) {}
//        });
//
//        mqttClient.subscribe("sensor/all_data");
//        mqttClient.subscribe("sensor/res_data");
//
//        // 1. 创建包含图片数据的传感器数据
//        SensorData sensorDataWithImage = createTestSensorData();
//        sensorDataWithImage.setSn("WITH_IMAGE");
//        sensorDataWithImage.setPicData(createTestImageData());
//
//        // 2. 创建不包含图片数据的传感器数据
//        SensorData sensorDataWithoutImage = createTestSensorData();
//        sensorDataWithoutImage.setSn("WITHOUT_IMAGE");
//        sensorDataWithoutImage.setPicData(null);
//
//        // 3. 将数据放入队列
//        String jsonWithImage = testMapper.writeValueAsString(sensorDataWithImage);
//        String jsonWithoutImage = testMapper.writeValueAsString(sensorDataWithoutImage);
//
//        RedisUtil.pushQueue(TEST_QUEUE, jsonWithImage);
//        RedisUtil.pushQueue(TEST_QUEUE, jsonWithoutImage);
//
//        // 4. 验证数据已放入队列
//        Long queueLength = jedis.llen(TEST_QUEUE);
//        assertEquals(2, queueLength.intValue(), "队列应该包含2条测试数据");
//
//        // 5. 启动消费者
//        RedisQueueConsumer consumer = new RedisQueueConsumer();
//        Thread consumerThread = new Thread(consumer, "RedisQueueConsumer-TopicTest");
//        consumerThread.setDaemon(true);
//        consumerThread.start();
//
//        // 6. 等待消费者处理消息并验证MQTT消息接收
//        boolean allDataReceived = allDataLatch.await(5, TimeUnit.SECONDS);
//        boolean resDataReceived = resDataLatch.await(5, TimeUnit.SECONDS);
//
//        assertTrue(allDataReceived, "应该接收到sensor/all_data主题的消息");
//        assertTrue(resDataReceived, "应该接收到sensor/res_data主题的消息");
//
//        // 7. 验证队列已清空
//        queueLength = jedis.llen(TEST_QUEUE);
//        assertEquals(0, queueLength.intValue(), "队列应该已被清空");
//
//        // 8. 中断消费者线程
//        consumerThread.interrupt();
//        try {
//            consumerThread.join(1000);
//        } catch (InterruptedException e) {
//            // 忽略
//        }
//    }
//
//    @Test
//    void testConsumerMqttMessageContent() throws Exception {
//        // 测试消费者发布的MQTT消息内容是否正确
//
//        // 使用CountDownLatch等待MQTT消息接收
//        CountDownLatch messageLatch = new CountDownLatch(1);
//        StringBuilder receivedMessage = new StringBuilder();
//        String[] receivedTopic = {""};
//
//        // 订阅MQTT主题以验证消息内容
//        mqttClient.setCallback(new MqttCallback() {
//            @Override
//            public void connectionLost(Throwable cause) {}
//
//            @Override
//            public void messageArrived(String topic, MqttMessage message) throws Exception {
//                receivedTopic[0] = topic;
//                receivedMessage.append(new String(message.getPayload()));
//                messageLatch.countDown();
//            }
//
//            @Override
//            public void deliveryComplete(IMqttDeliveryToken token) {}
//        });
//
//        mqttClient.subscribe("sensor/all_data");
//
//        // 1. 创建测试数据
//        SensorData sensorData = createTestSensorData();
//        sensorData.setSn("CONTENT_TEST");
//        sensorData.setPicData(createTestImageData());
//
//        // 2. 将数据放入队列
//        String jsonData = testMapper.writeValueAsString(sensorData);
//        RedisUtil.pushQueue(TEST_QUEUE, jsonData);
//
//        // 3. 启动消费者
//        RedisQueueConsumer consumer = new RedisQueueConsumer();
//        Thread consumerThread = new Thread(consumer, "RedisQueueConsumer-ContentTest");
//        consumerThread.setDaemon(true);
//        consumerThread.start();
//
//        // 4. 等待消费者处理消息
//        boolean messageReceived = messageLatch.await(5, TimeUnit.SECONDS);
//        assertTrue(messageReceived, "应该接收到MQTT消息");
//
//        // 5. 验证消息内容
//        assertEquals("sensor/all_data", receivedTopic[0], "应该发布到正确的主题");
//
//        // 验证消息内容可以被正确解析回SensorData对象
//        ObjectMapper consumerMapper = new ObjectMapper();
//        consumerMapper.registerModule(new JavaTimeModule());
//
//        assertDoesNotThrow(() -> {
//            SensorData parsedData = consumerMapper.readValue(receivedMessage.toString(), SensorData.class);
//            assertEquals("CONTENT_TEST", parsedData.getSn());
//            assertNotNull(parsedData.getPicData());
//        }, "MQTT消息内容应该能被正确解析为SensorData对象");
//
//        // 6. 验证队列已清空
//        Long queueLength = jedis.llen(TEST_QUEUE);
//        assertEquals(0, queueLength.intValue(), "队列应该已被清空");
//
//        // 7. 中断消费者线程
//        consumerThread.interrupt();
//        try {
//            consumerThread.join(1000);
//        } catch (InterruptedException e) {
//            // 忽略
//        }
//    }
//
//    private SensorData createTestSensorData() {
//        SensorData sensorData = new SensorData();
//        sensorData.setSn("TEST_SN_" + UUID.randomUUID().toString());
//        sensorData.setReserve("Test sensor data at " + System.currentTimeMillis());
//        sensorData.setType(1);
//        sensorData.sethVersion(2);
//        sensorData.setsVersion(3);
//        sensorData.setCcid("CCID123456");
//        sensorData.setTemperature(25);
//        sensorData.setHumidity(60);
//        sensorData.setVoltage(3300);
//        sensorData.setFlag(0);
//        sensorData.setStatus(1);
//        sensorData.setFreInfo(100);
//        sensorData.setFrePicCoef(50);
//        sensorData.setFreRes(200);
//        sensorData.setFrePic(300);
//        sensorData.setLight(80);
//        sensorData.setRsrp(-90);
//        sensorData.setSnr(15);
//        sensorData.setErrorCode("0000");
//        sensorData.setReserve("reserve");
//        sensorData.setU2p(1000);
//        sensorData.setResWidth(new Integer[]{100, 200, 300});
//        sensorData.setUptime(LocalDateTime.now());
//        sensorData.setPicSize(1024);
//
//        ArrayList<String> resList = new ArrayList<>();
//        resList.add("res1");
//        resList.add("res2");
//        sensorData.setResList(resList);
//
//        return sensorData;
//    }
//
//    private byte[] createTestImageData() {
//        // 创建测试图片数据
//        byte[] imageData = new byte[1024];
//        for (int i = 0; i < imageData.length; i++) {
//            imageData[i] = (byte) (i % 256);
//        }
//        return imageData;
//    }
//}
