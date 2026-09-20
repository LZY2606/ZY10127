package com.example.batterylab.service;

import com.example.batterylab.domain.StepType;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Parsed, immutable view of a protocol version JSON. */
public record ProtocolModel(List<Step> steps) {

    public record Step(StepType type, Long currentMa, Long limitMv, Long durationMs,
                       Integer jumpTo, Integer repetitions) {

        /** Zero-based index the LOOP jumps back to. */
        public int jumpIndex() {
            return jumpTo == null ? -1 : jumpTo - 1;
        }
    }

    public static ProtocolModel parse(JsonNode root) {
        JsonNode arr = root.get("steps");
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            throw new IllegalArgumentException("protocol must contain a non-empty steps array");
        }
        List<Step> steps = new ArrayList<>(arr.size());
        for (JsonNode n : arr) {
            StepType type = StepType.valueOf(n.get("type").asText());
            steps.add(new Step(
                    type,
                    longOrNull(n, "currentMa"),
                    longOrNull(n, "limitMv"),
                    longOrNull(n, "durationMs"),
                    intOrNull(n, "jumpTo"),
                    intOrNull(n, "repetitions")));
        }
        validate(steps);
        return new ProtocolModel(List.copyOf(steps));
    }

    private static void validate(List<Step> steps) {
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            switch (s.type()) {
                case CC_CHARGE -> require(s.currentMa() != null && s.limitMv() != null, i,
                        "CC_CHARGE needs currentMa and limitMv");
                case CV_CUTOFF -> require(s.currentMa() != null && s.limitMv() != null, i,
                        "CV_CUTOFF needs currentMa (cut-off) and limitMv (hold voltage)");
                case CC_DISCHARGE -> require(s.currentMa() != null && s.limitMv() != null, i,
                        "CC_DISCHARGE needs currentMa and limitMv");
                case REST -> require(s.durationMs() != null && s.durationMs() > 0, i,
                        "REST needs positive durationMs");
                case LOOP -> require(s.jumpTo() != null && s.jumpIndex() < i
                        && s.repetitions() != null && s.repetitions() > 0, i,
                        "LOOP needs jumpTo pointing at an earlier step and positive repetitions");
            }
        }
    }

    private static void require(boolean cond, int index, String msg) {
        if (!cond) {
            throw new IllegalArgumentException("invalid step " + (index + 1) + ": " + msg);
        }
    }

    private static Long longOrNull(JsonNode n, String field) {
        return n.has(field) && !n.get(field).isNull() ? n.get(field).asLong() : null;
    }

    private static Integer intOrNull(JsonNode n, String field) {
        return n.has(field) && !n.get(field).isNull() ? n.get(field).asInt() : null;
    }
}
