package com.lianyu.common.handler;

import com.baomidou.mybatisplus.extension.handlers.AbstractJsonTypeHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

public class JacksonListTypeHandler extends AbstractJsonTypeHandler<List<?>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JacksonListTypeHandler() {
        super(List.class);
    }

    public JacksonListTypeHandler(Class<?> type) {
        super(type);
    }

    @Override
    public List<?> parse(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<?>>() {});
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse JSON list", e);
        }
    }

    @Override
    public String toJson(List<?> obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert list to JSON", e);
        }
    }
}
