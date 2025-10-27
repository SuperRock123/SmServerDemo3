package org.toehold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.toehold.utils.RedisUtil;
import org.toehold.utils.Log;
import cn.zmvision.ccm.smserver.entitys.SensorData;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class RedisQueueConsumer implements Runnable {
    private final ObjectMapper mapper = new ObjectMapper();
    private MqttClient mqttClient;
    // 全局图片发送客户端（单例）
    private static MqttClient globalImageClient;
//    private final String broker = "tcp://mqtt.tongganyun.com:1883"; // Assumed Tonggan MQTT broker URL
    private final String broker = "tcp://localhost:1883"; // Assumed Tonggan MQTT broker URL
    private final String clientID = "WR0F202509180001"; // Example RTU code, update as needed
    private final String uName = "TOE"; // Company abbreviation, update as needed
    private  String uPwd;
/*========mqtt image client config==========*/
    private static final String imageBroker = "tcp://localhost:1883";
    private final String imageClientID = "WR0F202509180002-image";
    private final String imageUName = "TOE";
    //imageUPD为imageClientID的MD5 32位大写
    private static String imageUPD;
    private final String imageTopic = "$data/WR0F202509180001/image";


    //用来映射sn到longAddr,如果没有映射就用sn本身
    public static Map<String,String> mapping = new HashMap<>(){
        {
            put("TEST_SN_1","DT0E202509150001");
            put("TEST_SN_2","DT0E202509150002");
            put("TEST_SN_3","DT0E202509150003");
        }
    };
    public RedisQueueConsumer() {
        try {
            // Configure ObjectMapper to support Java 8 time types
            mapper.registerModule(new JavaTimeModule());

            // Compute uPwd as uppercase MD5 of clientID
            String plain = clientID.toUpperCase();
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(plain.getBytes("UTF-8"));
            uPwd = bytesToHex(digest).toUpperCase();

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
                String msg = RedisUtil.popQueue("sensor_queue");
                if (msg != null) {
                    SensorData data = mapper.readValue(msg, SensorData.class);
                    publishMqtt(data);
                } else {
                    Thread.sleep(100); // Sleep briefly if queue is empty
                }
            } catch (Exception e) {
                Log.error("Queue consumer error", e);
            }
        }
    }

    private void publishMqtt(SensorData data) {
        try {
            long ts = data.getUptime().atZone(ZoneId.of("UTC")).toInstant().toEpochMilli();

            // Map sn to longAddr (using sn directly if no mapping; extend map as needed)
            String longAddr1 = mapping.getOrDefault(data.getSn(), data.getSn());

            // Build comma-separated vals: [crack1, crack2, crack3, humidity, temperature]
            // Assuming resWidth in 0.01mm units (e.g., 31 -> 0.31mm)
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

            // Raw data payload for measurements
            Map<String, Object> dataMap = new HashMap<>();
            Map<String, Object> longAddressMap = new HashMap<>();
            Map<String, Object> timeToValMap = new HashMap<>();
            //convert sensorData.getUptime() to string timestamp in milliseconds
            timeToValMap.put(String.valueOf(ts), vals);// Comma-separated values in specified order

            longAddressMap.put(longAddr1, timeToValMap);
            dataMap.put("data", longAddressMap);
            String rawPayload = mapper.writeValueAsString(dataMap);
            String rawTopic = "$data/" + clientID + "/raw";
            MqttMessage rawMessage = new MqttMessage(rawPayload.getBytes());
            rawMessage.setQos(0);
            mqttClient.publish(rawTopic, rawMessage);
            Log.debug("Published raw data to [" + rawTopic + "]: " + rawPayload);

            // 图片数据：按协议 [$data/<mac>/image]，载荷为 [8字节设备地址][8字节时间戳(ms)][图片二进制]
            if (data.getPicData() != null && data.getPicData().length > 0) {
                byte[] imagePayload = buildImagePayload(longAddr1, ts, data.getPicData());
                MqttMessage imageMessage = new MqttMessage(imagePayload);
                imageMessage.setQos(0);
                ensureGlobalImageClient(imageBroker, imageUName, imageClientID); // 原来传入的是imageUPD，应该传imageClientID
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

    // 按协议构造图片上传载荷：[8字节设备地址][8字节时间戳(ms)][图片二进制]
    private byte[] buildImagePayload(String longAddr, long timestampMs, byte[] picData) {
        byte[] devBytes = to8ByteDeviceAddr(longAddr);
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8 + 8 + (picData != null ? picData.length : 0));
        buf.order(java.nio.ByteOrder.BIG_ENDIAN);
        buf.put(devBytes);
        buf.putLong(timestampMs);
        if (picData != null) buf.put(picData);
        return buf.array();
    }

    // 设备长地址编码为8字节：优先按十六进制解析，否则按UTF-8截断/补零
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
        // 其余补零
        for (int i = n; i < 8; i++) out[i] = 0;
        return out;
    }

    // 确保全局图片 MQTT 客户端已连接（单例）
    private static synchronized void ensureGlobalImageClient(String broker, String uName, String clientID) {
        try {
            if (globalImageClient == null || !globalImageClient.isConnected()) {
                // 为clientid添加3位随机数
                String finalClientId = clientID + "_" + new Random().nextInt(1000);
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] digest = md.digest(finalClientId.getBytes("UTF-8"));
                imageUPD = bytesToHex(digest).toUpperCase();

                globalImageClient = new MqttClient(broker, finalClientId, new MemoryPersistence());
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
        // JVM 退出时关闭全局图片客户端
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