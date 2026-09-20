package com.example.batterylab.service;

import com.example.batterylab.domain.Models.CapabilityDefinition;
import com.example.batterylab.domain.Models.ProtocolDefinition;
import com.example.batterylab.domain.Models.RuleDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class JsonService {
    private final ObjectMapper mapper;

    public JsonService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialize JSON", e);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot read JSON", e);
        }
    }

    public ProtocolDefinition protocol(String json) {
        return read(json, ProtocolDefinition.class);
    }

    public RuleDefinition rule(String json) {
        return read(json, RuleDefinition.class);
    }

    public CapabilityDefinition capability(String json) {
        return read(json, CapabilityDefinition.class);
    }
}
