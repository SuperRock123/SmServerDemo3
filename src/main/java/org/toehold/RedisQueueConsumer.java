package org.toehold;

import cn.zmvision.ccm.smserver.entitys.SensorData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.toehold.utils.AppConfig;
import org.toehold.utils.Log;
import org.toehold.utils.MqttReconnectClient;
import org.toehold.utils.RedisUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * 消费 Redis 队列（先新后旧），转发 MQTT。
 * 发布失败直接丢弃，保证积压时优先发送新数据。
 */
public class RedisQueueConsumer implements Runnable {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MqttReconnectClient rawMqttClient;
    private final MqttReconnectClient imageMqttClient;
    private final String RTU;
    private final String queue;
    private final int blockPopTimeoutSec;

    public RedisQueueConsumer() {
        mapper.registerModule(new JavaTimeModule());

        AppConfig.MqttConfig mqtt = AppConfig.mqtt();
        AppConfig.RedisConfig redis = AppConfig.redis();
        RTU = mqtt.RTU;
        queue = redis.queue;
        blockPopTimeoutSec = redis.blockPopTimeoutSec;
        int mqttReconnectSec = mqtt.reconnectIntervalSec;

        rawMqttClient = new MqttReconnectClient(
                "raw", mqtt.broker, mqtt.clientID, mqtt.userName, mqtt.password, mqttReconnectSec);
        imageMqttClient = new MqttReconnectClient(
                "image", mqtt.image.broker, mqtt.image.clientID, mqtt.image.userName,
                mqtt.image.password, mqttReconnectSec);

        rawMqttClient.start();
        imageMqttClient.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            rawMqttClient.close();
            imageMqttClient.close();
        }, "mqtt-shutdown"));
    }

    @Override
    public void run() {
        Log.debug("Queue consumer started: queue=" + queue
                + " maxLength=" + AppConfig.redis().maxLength
                + " (newest first, drop oldest on overflow)");

        while (!Thread.currentThread().isInterrupted()) {
            try {
                String msg = RedisUtil.blockPopNewest(queue, blockPopTimeoutSec);
                if (msg == null) {
                    continue;
                }
                SensorData data = mapper.readValue(msg, SensorData.class);
                publishMqtt(data);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                Log.error("Queue consumer error", e);
                sleepQuietly(AppConfig.redis().reconnectIntervalSec * 1000L);
            }
        }
        Log.debug("Queue consumer stopped");
    }

    private void publishMqtt(SensorData data) {
        try {
            long ts;
            String vals;

            if (data.getResList() != null && !data.getResList().isEmpty()) {
                String firstRes = data.getResList().get(0);
                String[] parts = firstRes.split(",");

                if (parts.length >= 1) {
                    try {
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmssSS");
                        Date date = sdf.parse(parts[0]);
                        ts = date.getTime();
                    } catch (ParseException e) {
                        Log.error("Invalid time format in resList, using current time.", e);
                        ts = System.currentTimeMillis();
                    }
                } else {
                    ts = System.currentTimeMillis();
                }

                StringBuilder sb = new StringBuilder();
                if (parts.length >= 4) {
                    for (int i = 1; i <= 3; i++) {
                        if (i > 1) sb.append(",");
                        double crackVal = Double.parseDouble(parts[i]);
                        sb.append(String.format("%.4f", crackVal));
                    }
                }
                sb.append(",").append(data.getHumidity() != null ? data.getHumidity() : 0)
                        .append(",").append(data.getTemperature() != null ? data.getTemperature() : 0);
                vals = sb.toString();
            } else {
                ts = System.currentTimeMillis();
                StringBuilder sb = new StringBuilder();
                Integer[] resWidths = data.getResWidth();
                if (resWidths != null && resWidths.length >= 3) {
                    for (int i = 0; i < 3; i++) {
                        if (i > 0) sb.append(",");
                        double crackVal = resWidths[i] / 100.0;
                        sb.append(String.format("%.3f", crackVal));
                    }
                }
                sb.append(",").append(data.getHumidity() != null ? data.getHumidity() : 0)
                        .append(",").append(data.getTemperature() != null ? data.getTemperature() : 0);
                vals = sb.toString();
            }

            String longAddr = (AppConfig.mqtt().mapping != null)
                    ? AppConfig.mqtt().mapping.getOrDefault(data.getSn(), data.getSn())
                    : data.getSn();

            Map<String, Object> dataMap = new HashMap<>();
            Map<String, Object> longAddressMap = new HashMap<>();
            Map<String, Object> timeToValMap = new HashMap<>();
            timeToValMap.put(String.valueOf(ts), vals);
            longAddressMap.put(longAddr, timeToValMap);
            dataMap.put("data", longAddressMap);
            String rawPayload = mapper.writeValueAsString(dataMap);
            String rawTopic = (AppConfig.mqtt().topic != null)
                    ? AppConfig.mqtt().topic.rawPrefix + RTU + AppConfig.mqtt().topic.rawSuffix
                    : ("$data/" + RTU + "/raw");

            rawMqttClient.publish(rawTopic, rawPayload.getBytes(), 0);
            Log.debug("Published raw to [" + rawTopic + "]: " + rawPayload);

            if (data.getPicData() != null && data.getPicData().length > 0) {
                byte[] imagePayload = buildImagePayload(longAddr, ts, data.getPicData());
                String imageTopic = (AppConfig.mqtt().topic != null)
                        ? AppConfig.mqtt().topic.imagePrefix + RTU + AppConfig.mqtt().topic.imageSuffix
                        : ("$data/" + RTU + "/image");
                imageMqttClient.publish(imageTopic, imagePayload, 0);
                Log.debug("Published image to [" + imageTopic + "] bytes=" + imagePayload.length);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 不回队：保证队列里始终优先处理更新的数据
            Log.error("publishMqtt failed, drop message sn=" + data.getSn(), e);
        }
    }

    private byte[] buildImagePayload(String longAddr, long timestampMs, byte[] picData) {
        byte[] devBytes = to8ByteDeviceAddr(longAddr);
        ByteBuffer buf = ByteBuffer.allocate(8 + 8 + (picData != null ? picData.length : 0));
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(devBytes);
        buf.putLong(timestampMs);
        if (picData != null) buf.put(picData);
        return buf.array();
    }

    private byte[] to8ByteDeviceAddr(String addr) {
        try {
            String hex = addr.replaceAll("[^0-9A-Fa-f]", "");
            if (hex.length() < 16) {
                hex = String.format("%16s", hex).replace(' ', '0');
            } else if (hex.length() > 16) {
                hex = hex.substring(0, 16);
            }
            if (hex.matches("[0-9A-Fa-f]{16}")) {
                byte[] data = new byte[8];
                for (int i = 0; i < 16; i += 2) {
                    data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                            + Character.digit(hex.charAt(i + 1), 16));
                }
                return data;
            }
        } catch (Exception e) {
            Log.error("设备地址转换失败: " + addr, e);
        }
        return new byte[8];
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
