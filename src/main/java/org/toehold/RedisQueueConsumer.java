package org.toehold;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.eclipse.paho.client.mqttv3.*;
import org.toehold.utils.RedisUtil;
import org.toehold.utils.Log;
import cn.zmvision.ccm.smserver.entitys.SensorData;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class RedisQueueConsumer implements Runnable {
    private final ObjectMapper mapper = new ObjectMapper();
    private MqttClient mqttClient;
//    private final String broker = "tcp://mqtt.tongganyun.com:1883"; // Assumed Tonggan MQTT broker URL
    private final String broker = "tcp://localhost:1883"; // Assumed Tonggan MQTT broker URL
    private final String clientID = "WR0F202509180001"; // Example RTU code, update as needed
    private final String uName = "TOE"; // Company abbreviation, update as needed
    private  String uPwd;
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

            mqttClient = new MqttClient(broker, clientID);
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setUserName(uName);
            opts.setPassword(uPwd.toCharArray());
            opts.setCleanSession(true);
            mqttClient.connect(opts);
            Log.debug("Connected to Tonggan Cloud MQTT");
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

//            // Event payload if picData present
//            if (data.getPicData() != null && data.getPicData().length > 0) {
//                String base64Pic = Base64.getEncoder().encodeToString(data.getPicData());
//                Map<String, Object> eventMap = new HashMap<>();
//                eventMap.put("devNo", clientID);
//                eventMap.put("captureMode", 301);
//                eventMap.put("picture", base64Pic);
//                eventMap.put("content", "裂缝监测事件"); // Event description for crack monitoring
//                eventMap.put("timestamp", ts);
//                String eventPayload = mapper.writeValueAsString(eventMap);
//                String eventTopic = "$request/" + clientID + "/cmd";
//                MqttMessage eventMessage = new MqttMessage(eventPayload.getBytes());
//                eventMessage.setQos(0);
//                mqttClient.publish(eventTopic, eventMessage);
//                Log.debug("Published event to [" + eventTopic + "]: " + eventPayload);
//            }
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
}