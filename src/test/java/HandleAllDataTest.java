
import cn.zmvision.ccm.smserver.entitys.SensorData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.toehold.ToeholdServerImp;
import org.toehold.utils.RedisUtil;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class HandleAllDataTest {

    private ToeholdServerImp server;
    private Jedis jedis;
    private static final String TEST_QUEUE = "sensor_queue";
    private static final DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");
    private ObjectMapper testMapper;

    @BeforeEach
    void setUp() {
        server = new ToeholdServerImp();
        jedis = new Jedis("localhost", 6379);
        jedis.flushAll(); // 清空Redis数据确保测试独立性

        // 创建支持 Java 8 时间类型的 ObjectMapper 用于测试验证
        testMapper = new ObjectMapper();
        testMapper.registerModule(new JavaTimeModule());
    }

    @AfterEach
    void tearDown() {
        if (jedis != null) {
            jedis.close();
        }
    }

    @Test
    void testHandleAllDataQueuesData() {
        // 测试 handle_all_data 方法是否将数据正确推送到队列
        SensorData sensorData = createTestSensorData();
        byte[] testImageData = createTestImageData();
        sensorData.setPicData(testImageData);

        String sn = sensorData.getSn();
        LocalDateTime now = LocalDateTime.now();
        String expectedFileName = sn + "_" + now.format(df) + ".jpg";
        Path expectedFilePath = Paths.get(expectedFileName);

        // 确保测试前文件不存在
        try {
            if (Files.exists(expectedFilePath)) {
                Files.delete(expectedFilePath);
            }
        } catch (IOException e) {
            // 忽略删除失败
        }

        // 调用 handle_all_data 方法
        server.handle_all_data(sensorData);

        // 验证数据已推送到Redis队列
        String jsonFromQueue = RedisUtil.popQueue(TEST_QUEUE);
        assertNotNull(jsonFromQueue, "应该从队列中获取到数据");
        assertFalse(jsonFromQueue.isEmpty(), "队列中的数据不应该为空");

        // 验证队列为空（确保只有一条数据）
        String emptyResult = RedisUtil.popQueue(TEST_QUEUE);
        assertNull(emptyResult, "队列应该为空");

        // 清理测试文件
        try {
            if (Files.exists(expectedFilePath)) {
                Files.delete(expectedFilePath);
            }
        } catch (IOException e) {
            // 忽略删除失败
        }
    }

    @Test
    void testHandleAllDataQueuedDataCanBeParsed() {
        // 测试 handle_all_data 方法推送到队列的数据是否可以被正确解析
        SensorData sensorData = createTestSensorData();
        byte[] testImageData = createTestImageData();
        sensorData.setPicData(testImageData);

        String sn = sensorData.getSn();
        LocalDateTime now = LocalDateTime.now();
        String expectedFileName = sn + "_" + now.format(df) + ".jpg";
        Path expectedFilePath = Paths.get(expectedFileName);

        // 确保测试前文件不存在
        try {
            if (Files.exists(expectedFilePath)) {
                Files.delete(expectedFilePath);
            }
        } catch (IOException e) {
            // 忽略删除失败
        }

        // 调用 handle_all_data 方法
        server.handle_all_data(sensorData);

        // 从队列中获取数据
        String jsonFromQueue = RedisUtil.popQueue(TEST_QUEUE);
        assertNotNull(jsonFromQueue, "应该从队列中获取到数据");

        // 验证JSON数据可以被正确解析回SensorData对象
        try {
            SensorData parsedSensorData = testMapper.readValue(jsonFromQueue, SensorData.class);
            assertSensorDataEquals(sensorData, parsedSensorData);
            assertNotNull(parsedSensorData.getPicData(), "解析后的数据应该包含图片数据");
        } catch (Exception e) {
            fail("队列中的JSON数据无法被正确解析: " + e.getMessage());
        }

        // 清理测试文件
        try {
            if (Files.exists(expectedFilePath)) {
                Files.delete(expectedFilePath);
            }
        } catch (IOException e) {
            // 忽略删除失败
        }
    }

    @Test
    void testHandleAllDataWithNullImageData() {
        // 测试 handle_all_data 方法处理无图片数据的情况
        SensorData sensorData = createTestSensorData();
        sensorData.setPicData(null); // 设置图片数据为null

        // 调用 handle_all_data 方法
        server.handle_all_data(sensorData);

        // 验证数据已推送到Redis队列
        String jsonFromQueue = RedisUtil.popQueue(TEST_QUEUE);
        assertNotNull(jsonFromQueue, "应该从队列中获取到数据");
        assertFalse(jsonFromQueue.isEmpty(), "队列中的数据不应该为空");

        // 验证JSON数据可以被正确解析
        try {
            SensorData parsedSensorData = testMapper.readValue(jsonFromQueue, SensorData.class);
            assertSensorDataEquals(sensorData, parsedSensorData);
            assertNull(parsedSensorData.getPicData(), "解析后的数据图片数据应该为null");
        } catch (Exception e) {
            fail("队列中的JSON数据无法被正确解析: " + e.getMessage());
        }
    }

    @Test
    void testHandleAllDataMultipleCalls() {
        // 测试多次调用 handle_all_data 方法
        int callCount = 3;

        // 多次调用 handle_all_data 方法
        for (int i = 0; i < callCount; i++) {
            SensorData sensorData = createTestSensorData();
            sensorData.setSn("SN_" + i);
            sensorData.setReserve("Test data " + i);
            server.handle_all_data(sensorData);
        }

        // 验证所有数据都已推送到队列（先进先出）
        for (int i = 0; i < callCount; i++) {
            String jsonFromQueue = RedisUtil.popQueue(TEST_QUEUE);
            assertNotNull(jsonFromQueue, "应该从队列中获取到数据");
            assertFalse(jsonFromQueue.isEmpty(), "队列中的数据不应该为空");

            // 验证JSON数据可以被正确解析
            try {
                SensorData parsedSensorData = testMapper.readValue(jsonFromQueue, SensorData.class);
                assertEquals("SN_" + i, parsedSensorData.getSn());
                assertEquals("Test data " + i, parsedSensorData.getReserve());
            } catch (Exception e) {
                fail("队列中的JSON数据无法被正确解析: " + e.getMessage());
            }
        }

        // 验证队列为空
        String emptyResult = RedisUtil.popQueue(TEST_QUEUE);
        assertNull(emptyResult, "队列应该为空");
    }


    private SensorData createTestSensorData() {
        SensorData sensorData = new SensorData();
        sensorData.setSn("TEST_SN_" + UUID.randomUUID().toString());
        sensorData.setReserve("Test sensor data at " + System.currentTimeMillis());
        sensorData.setType(1);
        sensorData.sethVersion(2);
        sensorData.setsVersion(3);
        sensorData.setCcid("CCID123456");
        sensorData.setTemperature(25);
        sensorData.setHumidity(60);
        sensorData.setVoltage(3300);
        sensorData.setFlag(0);
        sensorData.setStatus(1);
        sensorData.setFreInfo(100);
        sensorData.setFrePicCoef(50);
        sensorData.setFreRes(200);
        sensorData.setFrePic(300);
        sensorData.setLight(80);
        sensorData.setRsrp(-90);
        sensorData.setSnr(15);
        sensorData.setErrorCode("0000");
        sensorData.setReserve("reserve");
        sensorData.setU2p(1000);
        sensorData.setResWidth(new Integer[]{100, 200, 300});
        sensorData.setUptime(LocalDateTime.now());
        sensorData.setPicSize(1024);

        ArrayList<String> resList = new ArrayList<>();
        resList.add("res1");
        resList.add("res2");
        sensorData.setResList(resList);

        return sensorData;
    }

    private byte[] createTestImageData() {
        // 创建测试图片数据
        byte[] imageData = new byte[1024];
        for (int i = 0; i < imageData.length; i++) {
            imageData[i] = (byte) (i % 256);
        }
        return imageData;
    }

    private void assertSensorDataEquals(SensorData expected, SensorData actual) {
        assertEquals(expected.getSn(), actual.getSn());
        assertEquals(expected.getReserve(), actual.getReserve());
        assertEquals(expected.getType(), actual.getType());
        assertEquals(expected.gethVersion(), actual.gethVersion());
        assertEquals(expected.getsVersion(), actual.getsVersion());
        assertEquals(expected.getCcid(), actual.getCcid());
        assertEquals(expected.getTemperature(), actual.getTemperature());
        assertEquals(expected.getHumidity(), actual.getHumidity());
        assertEquals(expected.getVoltage(), actual.getVoltage());
        assertEquals(expected.getFlag(), actual.getFlag());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.getFreInfo(), actual.getFreInfo());
        assertEquals(expected.getFrePicCoef(), actual.getFrePicCoef());
        assertEquals(expected.getFreRes(), actual.getFreRes());
        assertEquals(expected.getFrePic(), actual.getFrePic());
        assertEquals(expected.getLight(), actual.getLight());
        assertEquals(expected.getRsrp(), actual.getRsrp());
        assertEquals(expected.getSnr(), actual.getSnr());
        assertEquals(expected.getErrorCode(), actual.getErrorCode());
        assertEquals(expected.getReserve(), actual.getReserve());
        assertEquals(expected.getU2p(), actual.getU2p());
        assertArrayEquals(expected.getResWidth(), actual.getResWidth());
        // 注意：由于序列化/反序列化，时间可能有微小差异，这里不做严格比较
        assertNotNull(actual.getUptime());
        assertEquals(expected.getPicSize(), actual.getPicSize());
        assertEquals(expected.getResList(), actual.getResList());
    }
}
