package org.toehold.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class AppConfig {
    public static class TcpConfig { public int port = 9911; }
    public static class RedisConfig { public String host = "localhost"; public int port = 6379; public String queue = "sensor_queue"; public String password; }
    public static class MqttConfig {
        public String broker = "tcp://localhost:1883";
        public String clientID = "WR0F202509180001";
        public String password;
        public String userName = "TOE";
        public TopicConfig topic = new TopicConfig();
        public ImageConfig image = new ImageConfig();
        public Map<String,String> mapping;
        public static class TopicConfig {
            public String rawPrefix = "$data/";
            public String rawSuffix = "/raw";
            public String imagePrefix = "$data/";
            public String imageSuffix = "/image";
        }
        public static class ImageConfig {
            public String broker = "tcp://localhost:1883";
            public String clientID = "WR0F202509180002-image";
            public String password;
            public String userName = "TOE";
            public boolean addRandomSuffix = true;
        }
    }
    public static class TestDataConfig { public boolean enabled = true; public String base64File = "logs/base64.txt"; }

    public TcpConfig tcp = new TcpConfig();
    public RedisConfig redis = new RedisConfig();
    public MqttConfig mqtt = new MqttConfig();
    public TestDataConfig testData = new TestDataConfig();

    private static volatile AppConfig INSTANCE = load();

    public static AppConfig get() { return INSTANCE; }
    public static TcpConfig tcp() { return get().tcp; }
    public static RedisConfig redis() { return get().redis; }
    public static MqttConfig mqtt() { return get().mqtt; }
    public static TestDataConfig testData() { return get().testData; }

    private static AppConfig load() {
        try {
            Path p = Paths.get("D:\\java\\SmServerDemo3\\config\\app.json");
            if (!Files.exists(p)) {
                p = Paths.get("config", "app.json");
            }
            byte[] bytes = Files.readAllBytes(p);
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(new String(bytes, StandardCharsets.UTF_8), AppConfig.class);
        } catch (Exception e) {
            Log.error("加载配置失败，使用默认配置", e);
            return new AppConfig();
        }
    }
}