package org.toehold.utils;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MQTT 客户端：连不上按固定间隔一直重试，直到成功。
 */
public class MqttReconnectClient implements MqttCallbackExtended, AutoCloseable {
    private static final int CONNECT_TIMEOUT_SEC = 30;
    private static final int KEEP_ALIVE_SEC = 60;

    private final String label;
    private final String broker;
    private final String clientId;
    private final String username;
    private final String password;
    private final long reconnectIntervalMs;

    private final Object connectLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile MqttClient client;
    private ScheduledExecutorService reconnectScheduler;

    public MqttReconnectClient(String label, String broker, String clientId,
                               String username, String password, int reconnectIntervalSec) {
        this.label = label;
        this.broker = broker;
        this.clientId = clientId;
        this.username = username;
        this.password = password;
        this.reconnectIntervalMs = Math.max(1, reconnectIntervalSec) * 1000L;
    }

    /** 启动后台固定间隔重连；连不上就一直重试。 */
    public void start() {
        connectOnce();
        reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mqtt-reconnect-" + label);
            t.setDaemon(true);
            return t;
        });
        reconnectScheduler.scheduleWithFixedDelay(() -> {
            if (!closed.get() && !isConnected()) {
                connectOnce();
            }
        }, reconnectIntervalMs, reconnectIntervalMs, TimeUnit.MILLISECONDS);
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    /**
     * 发布消息。未连接时按固定间隔重连，直到发出或线程中断。
     */
    public void publish(String topic, byte[] payload, int qos) throws MqttException, InterruptedException {
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            if (!isConnected()) {
                connectOnce();
            }
            if (!isConnected()) {
                Thread.sleep(reconnectIntervalMs);
                continue;
            }
            try {
                MqttMessage message = new MqttMessage(payload);
                message.setQos(qos);
                client.publish(topic, message);
                return;
            } catch (MqttException e) {
                Log.error(label + " MQTT publish failed, will reconnect", e);
                forceDisconnect();
                Thread.sleep(reconnectIntervalMs);
            }
        }
        throw new MqttException(MqttException.REASON_CODE_CLIENT_CLOSED);
    }

    private boolean connectOnce() {
        if (closed.get()) {
            return false;
        }
        synchronized (connectLock) {
            if (closed.get()) {
                return false;
            }
            try {
                if (client != null && client.isConnected()) {
                    return true;
                }
                if (client == null) {
                    client = new MqttClient(broker, clientId, new MemoryPersistence());
                    client.setCallback(this);
                }
                if (!client.isConnected()) {
                    client.connect(buildOptions());
                    Log.debug(label + " MQTT connected to " + broker);
                }
                return true;
            } catch (Exception e) {
                Log.error(label + " MQTT connect failed, retry in " + (reconnectIntervalMs / 1000) + "s", e);
                return false;
            }
        }
    }

    private void forceDisconnect() {
        synchronized (connectLock) {
            if (client == null) {
                return;
            }
            try {
                if (client.isConnected()) {
                    client.disconnectForcibly(1_000);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private MqttConnectOptions buildOptions() {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setUserName(username);
        if (password != null) {
            opts.setPassword(password.toCharArray());
        }
        opts.setCleanSession(true);
        opts.setAutomaticReconnect(true);
        opts.setKeepAliveInterval(KEEP_ALIVE_SEC);
        opts.setConnectionTimeout(CONNECT_TIMEOUT_SEC);
        opts.setMaxReconnectDelay((int) Math.min(reconnectIntervalMs, Integer.MAX_VALUE));
        return opts;
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        Log.debug(label + " MQTT connectComplete reconnect=" + reconnect + " uri=" + serverURI);
    }

    @Override
    public void connectionLost(Throwable cause) {
        if (cause instanceof Exception ex) {
            Log.error(label + " MQTT connection lost", ex);
        } else if (cause != null) {
            Log.error(label + " MQTT connection lost: " + cause.getMessage(), new Exception(cause));
        } else {
            Log.error(label + " MQTT connection lost", null);
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
    }

    @Override
    public void close() {
        closed.set(true);
        if (reconnectScheduler != null) {
            reconnectScheduler.shutdownNow();
            reconnectScheduler = null;
        }
        synchronized (connectLock) {
            if (client == null) {
                return;
            }
            try {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            } catch (Exception e) {
                Log.error(label + " MQTT close failed", e);
            } finally {
                client = null;
            }
        }
    }
}
