package org.toehold;

import cn.zmvision.ccm.smserver.entitys.SensorData;
import cn.zmvision.ccm.smserver.service.DataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.toehold.utils.Log;
import org.toehold.utils.RedisUtil;
import org.toehold.utils.AppConfig;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ToeholdServerImp implements DataService {
    private static final DateTimeFormatter df = DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss");
    private static final ObjectMapper mapper = new ObjectMapper();
    static {
        mapper.registerModule(new JavaTimeModule());
    }

    @Override
    public boolean check_allow_sn(String sn) {
        return true;
    }

    @Override
    public String take_downlink_sn(String sn) {
        return ""; // TODO: 可扩展
    }

    @Override
    public void handle_res_data(SensorData sensorData) {
        try {
            String json = mapper.writeValueAsString(sensorData);
            RedisUtil.pushQueue(AppConfig.redis().queue, json);
            Log.debug("RES_DATA queued to Redis: " + json);
        } catch (Exception e) {
            Log.error("handle_res_data failed", e);
        }
    }

    @Override
    public void handle_all_data(SensorData sensorData) {
//        savePicture(sensorData.getSn(), sensorData.getPicData());

        try {
            String json = mapper.writeValueAsString(sensorData);
            Log.debug("ALL_DATA queued to Redis: " + json);
            RedisUtil.pushQueue(AppConfig.redis().queue, json);
            Log.debug("ALL_DATA queued to Redis: " + json);
        } catch (Exception e) {
            Log.error("handle_all_data failed", e);
        }
    }

    private void savePicture(String sn, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;

        LocalDateTime now = LocalDateTime.now();
        Path path = Paths.get(sn + "_" + now.format(df) + ".jpg");
        try {
            Files.write(path, bytes, StandardOpenOption.CREATE);
            Log.debug("Picture saved: " + path);
        } catch (IOException e) {
            Log.error("savePicture failed", e);
        }
    }
}