package com.example.batterylab.domain;

import java.util.List;

public final class Models {
    private Models() {
    }

    public enum RunState {
        RUNNING, PAUSED, COMPLETED, CANCELLED, DEVICE_FAULT, CONFLICT
    }

    public enum InterruptionReason {
        NONE, PAUSED, CANCELLED, DEVICE_FAULT, CONFLICT, COMPLETED, LATE_SAMPLE
    }

    public enum SampleStatus {
        PENDING, CONFIRMED, LATE, CONFLICT
    }

    public record ProtocolStep(
            int index,
            String name,
            String type,
            long durationMs,
            double currentA,
            Double targetVoltageV,
            Double cutoffCurrentA,
            Integer jumpTo
    ) {
    }

    public record ProtocolDefinition(
            String name,
            long sampleIntervalMs,
            int cycleLimit,
            List<ProtocolStep> steps
    ) {
    }

    public record RuleDefinition(
            String integrationMethod,
            String endpointPolicy,
            String positiveCurrentDirection,
            boolean thresholdTransferOnlyOnConfirmedCrossing,
            boolean splitIntervalsAtCycleBoundary
    ) {
    }

    public record CapabilityDefinition(
            String deviceSn,
            double minVoltageV,
            double maxVoltageV,
            double minCurrentA,
            double maxCurrentA,
            double minTemperatureC,
            double maxTemperatureC,
            long sampleIntervalMs
    ) {
    }
}
