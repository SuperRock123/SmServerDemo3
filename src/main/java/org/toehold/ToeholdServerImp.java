package org.toehold;

import cn.zmvision.ccm.smserver.entitys.SensorData;
import cn.zmvision.ccm.smserver.service.DataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.toehold.utils.AppConfig;
import org.toehold.utils.Log;
import org.toehold.utils.RedisUtil;

public class ToeholdServerImp implements DataService {
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
        return "";
    }

    @Override
    public void handle_res_data(SensorData sensorData) {
        enqueue(sensorData, "RES_DATA");
    }

    @Override
    public void handle_all_data(SensorData sensorData) {
        enqueue(sensorData, "ALL_DATA");
    }

    private void enqueue(SensorData sensorData, String tag) {
        try {
            String json = mapper.writeValueAsString(sensorData);
            RedisUtil.pushQueue(AppConfig.redis().queue, json);
            Log.debug(tag + " queued, sn=" + sensorData.getSn());
        } catch (Exception e) {
            Log.error(tag + " enqueue failed, sn=" + sensorData.getSn(), e);
        }
    }
}
