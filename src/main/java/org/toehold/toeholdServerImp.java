package org.toehold;

import cn.zmvision.ccm.smserver.entitys.SensorData;
import cn.zmvision.ccm.smserver.server.SmServer;
import cn.zmvision.ccm.smserver.service.DataService;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class toeholdServerImp implements DataService {
    private static final DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");
    private MqttClient mqttClient;

    // MQTT 初始化
    public toeholdServerImp() {
        try {
            mqttClient = new MqttClient("tcp://localhost:1883", MqttClient.generateClientId());
            MqttConnectOptions connOpts = new MqttConnectOptions();
            connOpts.setCleanSession(true);
            mqttClient.connect(connOpts);
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    // 是否允许对应设备编号的数据通过,可用于鉴权.true通过,false拒绝
    @Override
    public boolean check_allow_sn(String sn) {
        return true;
    }

    //1.该接口用于给设备下发指令,
    //2.指令请采用消费模式,用后即删,避免重复下发;
    //3.指令的创建,可以用工具类生成   有线版本:MsgHighTemplate  无线版本:MsgTemplate
    @Override
    public String take_downlink_sn(String sn) {//可以通过
        String msg = "";
        return msg;
    }

    // 获取设备的图片数据,同时包含了设备状态信息;该接口只在图片传输成功后调用;
    @Override
    public void handle_all_data(SensorData sensorData) {
        System.out.println(sensorData);
        String sn = sensorData.getSn();
        savePicture(sn, sensorData.getPicData());

        // 将数据通过 MQTT 发送
        String payload = String.format("sn=%s, uptime=%s, picSize=%d", sn, sensorData.getUptime(), sensorData.getPicSize());
        publishMqttMessage("sensor/all_data", payload);
    }

    // 1.仅有线版本:获取结果数据,不含图片数据;
    // 2.结果数据在resList属性中,包含多条数据,格式: uptime,w1,w2,w3;
    // 3.注意:因为高频采集的缘故,该方法的实现不能复杂耗时;若有有耗时逻辑,建议利用异步或消息处理机制实现;
    @Override
    public void handle_res_data(SensorData sensorData) {
        System.out.println(sensorData);

        // 处理 resList 数据并通过 MQTT 发送
        if (sensorData.getResList() != null && !sensorData.getResList().isEmpty()) {
            String resData = String.join(",", sensorData.getResList());
            String payload = String.format("sn=%s, uptime=%s, resData=%s", sensorData.getSn(), sensorData.getUptime(), resData);
            publishMqttMessage("sensor/res_data", payload);
        }
    }

    private void savePicture(String sn, byte[] bytes) {
        LocalDateTime now = LocalDateTime.now();
        Path path = Paths.get(sn + "_" + now.format(df) + ".jpg");
        if (!Files.exists(path)) {
            try {
                Files.createFile(path);
                Files.write(path, bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void publishMqttMessage(String topic, String payload) {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                MqttMessage message = new MqttMessage(payload.getBytes());
                message.setQos(0); // QoS 0: 最多分发一次
                mqttClient.publish(topic, message);
                System.out.println("Published to " + topic + ": " + payload);
            }
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    // 关闭 MQTT 连接（可选，在 SmServer 停止时调用）
    public void disconnect() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
            }
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }
}