
import cn.zmvision.ccm.smserver.entitys.SensorData;
import com.fasterxml.jackson.databind.ObjectMapper;
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

public class ToeholdServerImpTest {

    private ToeholdServerImp server;
    private Jedis jedis;
    private static final String TEST_QUEUE = "sensor_queue";
    private static final DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");

    @BeforeEach
    void setUp() {
        server = new ToeholdServerImp();
        jedis = new Jedis("localhost", 6379);
        jedis.flushAll(); // 清空Redis数据确保测试独立性
    }

    @AfterEach
    void tearDown() {
        if (jedis != null) {
            jedis.close();
        }
    }

    @Test
    void testCheckAllowSn() {
        // 测试设备SN验证功能
        String testSn = "TEST_SN_" + UUID.randomUUID().toString();
        boolean result = server.check_allow_sn(testSn);
        assertTrue(result, "应该允许所有SN");
    }

    @Test
    void testTakeDownlinkSn() {
        // 测试下行SN获取功能
        String testSn = "TEST_SN_" + UUID.randomUUID().toString();
        String result = server.take_downlink_sn(testSn);
        assertEquals("", result, "当前实现应该返回空字符串");
    }

    @Test
    void testHandleResData() {
        // 测试处理传感器数据（仅数据）
        SensorData sensorData = createTestSensorData();
        sensorData.setPicData(null); // 确保没有图片数据

        // 执行处理
        server.handle_res_data(sensorData);

        // 验证数据已推送到Redis队列
        String jsonFromQueue = RedisUtil.popQueue(TEST_QUEUE);
        assertNotNull(jsonFromQueue, "应该从队列中获取到数据");

        // 验证JSON内容
        ObjectMapper mapper = new ObjectMapper();
        try {
            SensorData resultData = mapper.readValue(jsonFromQueue, SensorData.class);
            assertSensorDataEquals(sensorData, resultData);
            assertNull(resultData.getPicData());
        } catch (Exception e) {
            fail("JSON解析失败: " + e.getMessage());
        }

        // 验证队列为空
        String emptyResult = RedisUtil.popQueue(TEST_QUEUE);
        assertNull(emptyResult, "队列应该为空");
    }

    @Test
    void testHandleAllData() throws IOException {
        // 测试处理完整传感器数据（含图片）
        SensorData sensorData = createTestSensorData();
        byte[] testImageData = createTestImageData();
        sensorData.setPicData(testImageData);

        // 记录处理前的图片数量
        String sn = sensorData.getSn();
        LocalDateTime now = LocalDateTime.now();
        String expectedFileName = sn + "_" + now.format(df) + ".jpg";
        Path expectedFilePath = Paths.get(expectedFileName);

        // 确保测试前文件不存在
        if (Files.exists(expectedFilePath)) {
            Files.delete(expectedFilePath);
        }

        // 执行处理
        server.handle_all_data(sensorData);

        // 验证图片文件已保存
        assertTrue(Files.exists(expectedFilePath), "应该创建图片文件");
        byte[] savedImageData = Files.readAllBytes(expectedFilePath);
        assertArrayEquals(testImageData, savedImageData, "保存的图片数据应该与原始数据一致");

        // 验证数据已推送到Redis队列
        String jsonFromQueue = RedisUtil.popQueue(TEST_QUEUE);
        assertNotNull(jsonFromQueue, "应该从队列中获取到数据");

        // 验证JSON内容
        ObjectMapper mapper = new ObjectMapper();
        try {
            SensorData resultData = mapper.readValue(jsonFromQueue, SensorData.class);
            assertSensorDataEquals(sensorData, resultData);
            assertNotNull(resultData.getPicData());
        } catch (Exception e) {
            fail("JSON解析失败: " + e.getMessage());
        }

        // 清理测试文件
        if (Files.exists(expectedFilePath)) {
            Files.delete(expectedFilePath);
        }

        // 验证队列为空
        String emptyResult = RedisUtil.popQueue(TEST_QUEUE);
        assertNull(emptyResult, "队列应该为空");
    }

    @Test
    void testHandleAllDataWithoutImage() {
        // 测试处理无图片数据的传感器数据
        SensorData sensorData = createTestSensorData();
        sensorData.setPicData(null);

        // 执行处理
        server.handle_all_data(sensorData);

        // 验证数据已推送到Redis队列
        String jsonFromQueue = RedisUtil.popQueue(TEST_QUEUE);
        assertNotNull(jsonFromQueue, "应该从队列中获取到数据");

        // 验证JSON内容
        ObjectMapper mapper = new ObjectMapper();
        try {
            SensorData resultData = mapper.readValue(jsonFromQueue, SensorData.class);
            assertSensorDataEquals(sensorData, resultData);
            assertNull(resultData.getPicData());
        } catch (Exception e) {
            fail("JSON解析失败: " + e.getMessage());
        }
    }

    @Test
    void testMultipleSensorDataHandling() {
        // 测试处理多个传感器数据
        int dataCount = 5;

        // 推送多个数据
        for (int i = 0; i < dataCount; i++) {
            SensorData sensorData = createTestSensorData();
            sensorData.setSn("SN_" + i);
            sensorData.setReserve("Data_" + i);
            server.handle_res_data(sensorData);
        }

        // 验证所有数据都已推送到队列（后进先出）
        for (int i = dataCount - 1; i >= 0; i--) {
            String jsonFromQueue = RedisUtil.popQueue(TEST_QUEUE);
            assertNotNull(jsonFromQueue, "应该从队列中获取到数据");

            ObjectMapper mapper = new ObjectMapper();
            try {
                SensorData resultData = mapper.readValue(jsonFromQueue, SensorData.class);
                assertEquals("SN_" + i, resultData.getSn());
                assertEquals("Data_" + i, resultData.getReserve());
            } catch (Exception e) {
                fail("JSON解析失败: " + e.getMessage());
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
        assertEquals(expected.getUptime(), actual.getUptime());
        assertEquals(expected.getPicSize(), actual.getPicSize());
        assertEquals(expected.getResList(), actual.getResList());
    }
}
