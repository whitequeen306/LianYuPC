package com.lianyu.common.handler;

import com.baomidou.mybatisplus.extension.handlers.AbstractJsonTypeHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

public class JacksonTypeHandler extends AbstractJsonTypeHandler<Object> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JacksonTypeHandler() {
        super(Object.class);
    }

    public JacksonTypeHandler(Class<?> type) {
        super(type);
    }

    @Override
    public Object parse(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse JSON", e);
        }
    }

    @Override
    public String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert to JSON", e);
        }
    }
}
