package org.toehold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.toehold.utils.RedisUtil;
import org.toehold.utils.Log;
import org.toehold.utils.AppConfig;
import cn.zmvision.ccm.smserver.entitys.SensorData;

import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class RedisQueueConsumer implements Runnable {
    private final ObjectMapper mapper = new ObjectMapper();
    private MqttClient mqttClient;
    private static MqttClient globalImageClient;

    private String broker;
    private String clientID;
    private String uName;

    private String imageBroker;
    private String imageClientID;
    private String imageUName;
    private boolean imageAddRandomSuffix;
    private  String RTU="WR01202509150001";
    public RedisQueueConsumer() {
        try {
            mapper.registerModule(new JavaTimeModule());

            // 从配置加载参数
            broker = AppConfig.mqtt().broker;
            clientID = AppConfig.mqtt().clientID;
            uName = AppConfig.mqtt().userName;
            imageBroker = AppConfig.mqtt().image.broker;
            imageClientID = AppConfig.mqtt().image.clientID;
            imageUName = AppConfig.mqtt().image.userName;
            imageAddRandomSuffix = AppConfig.mqtt().image.addRandomSuffix;
            RTU=AppConfig.mqtt().RTU;
            // Raw client password: MD5(uppercase clientID)
//            String plain = clientID.toUpperCase();
//            MessageDigest md = MessageDigest.getInstance("MD5");
//            byte[] digest = md.digest(plain.getBytes("UTF-8"));
//            String uPwd = bytesToHex(digest).toUpperCase();

            String uPwd = AppConfig.mqtt().password;
            mqttClient = new MqttClient(broker, clientID, new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setUserName(uName);
            opts.setPassword(uPwd.toCharArray());
            opts.setCleanSession(true);
            mqttClient.connect(opts);
            Log.debug("Connected to MQTT");
        } catch (Exception e) {
            Log.error("MQTT connect failed", e);
        }
    }

    @Override
    public void run() {
        while (true) {
            try {
                String msg = RedisUtil.popQueue(AppConfig.redis().queue);
                if (msg != null) {
                    SensorData data = mapper.readValue(msg, SensorData.class);
                    publishMqtt(data);
                } else {
                    Thread.sleep(100);
                }
            } catch (Exception e) {
                Log.error("Queue consumer error", e);
            }
        }
    }

    private void publishMqtt(SensorData data) {
        try {
            long ts = data.getUptime().atZone(ZoneId.of("UTC")).toInstant().toEpochMilli();

            String longAddr1 = (AppConfig.mqtt().mapping != null)
                    ? AppConfig.mqtt().mapping.getOrDefault(data.getSn(), data.getSn())
                    : data.getSn();

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
            String vals = sb.toString();

            Map<String, Object> dataMap = new HashMap<>();
            Map<String, Object> longAddressMap = new HashMap<>();
            Map<String, Object> timeToValMap = new HashMap<>();
            timeToValMap.put(String.valueOf(ts), vals);
            longAddressMap.put(longAddr1, timeToValMap);
            dataMap.put("data", longAddressMap);
            String rawPayload = mapper.writeValueAsString(dataMap);
            String rawTopic = (AppConfig.mqtt().topic != null)
                    ? AppConfig.mqtt().topic.rawPrefix + RTU + AppConfig.mqtt().topic.rawSuffix
                    : ("$data/" + RTU + "/raw");
            MqttMessage rawMessage = new MqttMessage(rawPayload.getBytes());
            rawMessage.setQos(0);
            mqttClient.publish(rawTopic, rawMessage);
            Log.debug("Published raw data to [" + rawTopic + "]: " + rawPayload);

            if (data.getPicData() != null && data.getPicData().length > 0) {
                byte[] imagePayload = buildImagePayload(longAddr1, ts, data.getPicData());
                MqttMessage imageMessage = new MqttMessage(imagePayload);
                imageMessage.setQos(0);
                ensureGlobalImageClient(imageBroker, imageUName, imageClientID, imageAddRandomSuffix);
                String imageTopic = (AppConfig.mqtt().topic != null)
                        ? AppConfig.mqtt().topic.imagePrefix + RTU + AppConfig.mqtt().topic.imageSuffix
                        : ("$data/" + RTU + "/image");
                globalImageClient.publish(imageTopic, imageMessage);
                Log.debug("Published image to [" + imageTopic + "] bytes=" + imagePayload.length);
            }
        } catch (Exception e) {
            Log.error("publishMqtt failed", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private byte[] buildImagePayload(String longAddr, long timestampMs, byte[] picData) {
        byte[] devBytes = to8ByteDeviceAddr(longAddr);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8 + 8 + (picData != null ? picData.length : 0));
        buf.order(java.nio.ByteOrder.BIG_ENDIAN);
        buf.put(devBytes);
        buf.putLong(timestampMs);
        if (picData != null) buf.put(picData);
        return buf.array();
    }

    private byte[] to8ByteDeviceAddr(String addr) {
        try {
            String hex = addr.replaceAll("[^0-9A-Fa-f]", "");
            if (hex.length() >= 16 && hex.matches("[0-9A-Fa-f]+")) {
                byte[] out = new byte[8];
                for (int i = 0; i < 8; i++) {
                    out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                }
                return out;
            }
        } catch (Exception ignored) {}
        byte[] src = addr.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[8];
        int n = Math.min(src.length, 8);
        System.arraycopy(src, 0, out, 0, n);
        for (int i = n; i < 8; i++) out[i] = 0;
        return out;
    }

    private static synchronized void ensureGlobalImageClient(String broker, String uName, String clientID, boolean addRandomSuffix) {
        try {
            if (globalImageClient == null || !globalImageClient.isConnected()) {
//                String finalClientId = clientID + (addRandomSuffix ? ("_" + new Random().nextInt(1000)) : "");
//                MessageDigest md = MessageDigest.getInstance("MD5");
//                byte[] digest = md.digest(clientID.getBytes("UTF-8"));
//                String imageUPD = bytesToHex(digest).toUpperCase();

                String imageUPD = AppConfig.mqtt().image.password;
                globalImageClient = new MqttClient(broker, clientID, new MemoryPersistence());
                MqttConnectOptions opts = new MqttConnectOptions();
                opts.setUserName(uName);
                opts.setPassword(imageUPD.toCharArray());
                opts.setCleanSession(true);
                globalImageClient.connect(opts);
                Log.debug("Global image MQTT client connected");
            }
        } catch (Exception e) {
            Log.error("Global image MQTT connect failed", e);
            throw new RuntimeException(e);
        }
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (globalImageClient != null && globalImageClient.isConnected()) {
                    globalImageClient.disconnect();
                    globalImageClient.close();
                }
            } catch (Exception ignored) {}
        }));
    }
}