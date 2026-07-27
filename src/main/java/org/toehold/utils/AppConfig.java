package org.toehold.utils;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class AppConfig {
    public static class TcpConfig {
        public int port = 9911;
    }

    public static class RedisConfig {
        public String host = "localhost";
        public int port = 6379;
        public String queue = "sensor_queue";
        public String password;
        /** 队列最大条数，超限丢最旧；0=不限制 */
        public int maxLength = 10_000;
        /** BLPOP 阻塞秒数 */
        public int blockPopTimeoutSec = 5;
        /** Redis 连不上时的固定重试间隔（秒） */
        public int reconnectIntervalSec = 5;
    }

    public static class MqttConfig {
        public String broker = "tcp://localhost:1883";
        public String clientID = "WR0F202509180001";
        public String password;
        public String userName = "TOE";
        public String RTU;
        /** MQTT 连不上时的固定重试间隔（秒） */
        public int reconnectIntervalSec = 5;
        public TopicConfig topic = new TopicConfig();
        public ImageConfig image = new ImageConfig();
        public Map<String, String> mapping;

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

    public static class TestDataConfig {
        public boolean enabled = false;
        public String base64File = "logs/base64.txt";
    }

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
            Path p = resolveConfigPath();
            byte[] bytes = Files.readAllBytes(p);
            ObjectMapper mapper = new ObjectMapper();
            AppConfig cfg = mapper.readValue(new String(bytes, StandardCharsets.UTF_8), AppConfig.class);
            normalize(cfg);
            Log.debug("Loaded config from " + p.toAbsolutePath());
            return cfg;
        } catch (Exception e) {
            Log.error("加载配置失败，使用默认配置", e);
            AppConfig defaults = new AppConfig();
            normalize(defaults);
            return defaults;
        }
    }

    private static void normalize(AppConfig cfg) {
        if (cfg.redis.blockPopTimeoutSec < 1) {
            cfg.redis.blockPopTimeoutSec = 5;
        }
        if (cfg.redis.reconnectIntervalSec < 1) {
            cfg.redis.reconnectIntervalSec = 5;
        }
        if (cfg.mqtt.reconnectIntervalSec < 1) {
            cfg.mqtt.reconnectIntervalSec = 5;
        }
    }

    private static Path resolveConfigPath() {
        String envPath = System.getenv("CONFIG_PATH");
        if (envPath != null && !envPath.isBlank()) {
            Path p = Paths.get(envPath);
            if (Files.exists(p)) {
                return p;
            }
            Log.error("CONFIG_PATH 不存在: " + p.toAbsolutePath(), null);
        }
        Path dockerPath = Paths.get("/app/config/app.json");
        if (Files.exists(dockerPath)) {
            return dockerPath;
        }
        Path local = Paths.get("config", "app.json");
        if (Files.exists(local)) {
            return local;
        }
        return local;
    }
}
