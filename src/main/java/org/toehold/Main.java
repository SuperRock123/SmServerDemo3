package org.toehold;

import cn.zmvision.ccm.smserver.entitys.SensorData;
import cn.zmvision.ccm.smserver.server.SmServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.toehold.utils.Log;
import org.toehold.utils.RedisUtil;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Main {
    private static final ObjectMapper mapper = new ObjectMapper();

    static {
        mapper.registerModule(new JavaTimeModule());
    }

    public static void main(String[] args) {
        Log.debug("Server starting...");

        ToeholdServerImp service = new ToeholdServerImp();
        SmServer smServer = new SmServer(service, 9911);

        // 启动Redis队列消费者
        new Thread(new RedisQueueConsumer(), "RedisQueueConsumer").start();

        // 延迟插入测试数据
        scheduleTestDataInsertion();
        // 启动服务
        smServer.start();


        Log.debug("Server started on port 9911");
    }

    private static void scheduleTestDataInsertion() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        // 延迟5秒后执行
        scheduler.schedule(() -> {
            try {
                insertTestSensorData();
            } catch (Exception e) {
                Log.error("Failed to insert test sensor data", e);
            }
        }, 5, TimeUnit.SECONDS);
    }

    private static void insertTestSensorData() {
        Log.debug("开始插入测试SensorData数据到Redis队列");

        try {
            // 创建多个测试SensorData对象
            for (int i = 0; i < 5; i++) {
                SensorData sensorData = createTestSensorData(i);
                String json = mapper.writeValueAsString(sensorData);
                RedisUtil.pushQueue("sensor_queue", json);
                Log.debug("已插入测试数据到Redis队列: " + sensorData.getSn());

                // 添加少量延迟以模拟实际数据到达间隔
                Thread.sleep(500);
            }

            Log.debug("所有测试数据已成功插入Redis队列");
        } catch (Exception e) {
            Log.error("插入测试SensorData数据时发生错误", e);
        }
    }

    private static SensorData createTestSensorData(int index) {
        SensorData sensorData = new SensorData();
        sensorData.setSn("TEST_SN_" + index);
        sensorData.setType(1);
        sensorData.sethVersion(2);
        sensorData.setsVersion(3);
        sensorData.setCcid("CCID_TEST_" + index);
        sensorData.setTemperature(20 + index); // 20-24度
        sensorData.setHumidity(50 + index); // 50-54%
        sensorData.setVoltage(3300 + index * 10); // 3300-3340mV
        sensorData.setFlag(0);
        sensorData.setStatus(1);
        sensorData.setFreInfo(100);
        sensorData.setFrePicCoef(50);
        sensorData.setFreRes(200);
        sensorData.setFrePic(300);
        sensorData.setLight(70 + index);
        sensorData.setRsrp(-80 - index); // -80到-84
        sensorData.setSnr(20 - index); // 20-16
        sensorData.setErrorCode("0000");
        sensorData.setReserve("Test data " + index);
        sensorData.setU2p(1000 + index * 100);
        sensorData.setResWidth(new Integer[]{100 + index * 10, 200 + index * 10, 300 + index * 10});
        sensorData.setUptime(LocalDateTime.now());
        sensorData.setPicSize(1024 + index * 512);

        // 为部分数据添加图片数据
        if (index % 2 == 0) {
            byte[] picData = createTestImageData(512 + index * 256);
            sensorData.setPicData(picData);
        } else {
            sensorData.setPicData(null);
        }

        ArrayList<String> resList = new ArrayList<>();
        resList.add("res_item_1_" + index);
        resList.add("res_item_2_" + index);
        sensorData.setResList(resList);

        return sensorData;
    }

    private static byte[] createTestImageData(int size) {
        byte[] imageData = new byte[size];
        for (int i = 0; i < size; i++) {
            imageData[i] = (byte) (i % 256);
        }
        return imageData;
    }
}
