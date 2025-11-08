package org.toehold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.toehold.utils.RedisUtil;
import org.toehold.utils.Log;
import org.toehold.utils.AppConfig;
import cn.zmvision.ccm.smserver.entitys.SensorData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.util.Date;
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
            long ts;
            String vals;

            // 检查是否有resList且不为空
            if (data.getResList() != null && !data.getResList().isEmpty()) {
                String firstRes = data.getResList().get(0);
                String[] parts = firstRes.split(",");

                // 从resList的第一个元素中解析时间戳
                if (parts.length >= 1) {
                    String timeStr = parts[0];
                    try {
                        // 解析时间格式: yyyyMMdd_HHmmssSS
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmssSS");
                        Date date = sdf.parse(timeStr);
                        ts = date.getTime(); // 获取毫秒时间戳
                    } catch (ParseException e) {
                        e.printStackTrace();
                        Log.error("Invalid time format in resList, using current time.",e);
                        ts = System.currentTimeMillis(); // 解析失败使用当前时间
                    }
                } else {
                    ts = System.currentTimeMillis();
                }

                // 保存完整的数据值
                vals = firstRes;

                // 构建值字符串：使用resList中的指定位置元素
                StringBuilder sb = new StringBuilder();
                if (parts.length >= 4) {
                    // 使用resList[1], resList[2], resList[3]作为裂缝宽度值
                    for (int i = 1; i <= 3; i++) {
                        if (i > 1) sb.append(",");
                        double crackVal = Double.parseDouble(parts[i]);
                        sb.append(String.format("%.4f", crackVal));
                    }
                }

                // 添加湿度和温度
                sb.append(",").append(data.getHumidity() != null ? data.getHumidity() : 0)
                        .append(",").append(data.getTemperature() != null ? data.getTemperature() : 0);
                vals = sb.toString();
            } else {
                // 如果没有resList，则回退到原始逻辑（但仍然处理uptime为null的情况）
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

            String longAddr1 = (AppConfig.mqtt().mapping != null)
                    ? AppConfig.mqtt().mapping.getOrDefault(data.getSn(), data.getSn())
                    : data.getSn();

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

            // 处理 图片
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
        ByteBuffer buf = ByteBuffer.allocate(8 + 8 + (picData != null ? picData.length : 0));
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(devBytes);
        buf.putLong(timestampMs);  // 大端序写入
        if (picData != null) buf.put(picData);
        return buf.array();
    }

    private byte[] to8ByteDeviceAddr(String addr) {
        try {
            // 移除所有非十六进制字符
            String hex = addr.replaceAll("[^0-9A-Fa-f]", "");

            // 确保正好16个十六进制字符（8字节）
            if (hex.length() < 16) {
                // 左侧补0
                hex = String.format("%16s", hex).replace(' ', '0');
            } else if (hex.length() > 16) {
                // 截断到16字符
                hex = hex.substring(0, 16);
            }

            // 验证格式
            if (hex.matches("[0-9A-Fa-f]{16}")) {
                int len = hex.length();
                byte[] data = new byte[len / 2];
                for (int i = 0; i < len; i += 2) {
                    data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                            + Character.digit(hex.charAt(i + 1), 16));
                }
                return data;
            }
        } catch (Exception e) {
            System.err.println("设备地址转换失败: " + addr + ", 错误: " + e.getMessage());
        }

        // 失败时返回8字节0
        return new byte[8];
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