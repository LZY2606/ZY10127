package com.example.batterylab.dto;

import java.util.List;

public final class Dtos {
    private Dtos() {
    }

    public record CreateRunRequest(String protocolVersion, String ruleVersion, String capabilityVersion) {
    }

    public record SampleRequest(
            long sequenceNumber,
            long sampledAtMs,
            double voltageV,
            double currentA,
            double temperatureC
    ) {
    }

    public record BoundaryRequest(long boundaryAtMs, String reason) {
    }

    public record ExclusionRequest(long startAtMs, long endAtMs, String reason) {
    }

    public record RecomputeRequest(String ruleVersion) {
    }

    public record VersionRef(String id, String version, String name) {
    }

    public record CycleResultResponse(
            int cycleIndex,
            Long startAtMs,
            Long endAtMs,
            double chargeAh,
            double dischargeAh,
            Double coulombicEfficiency,
            int sampleCount,
            long excludedMs
    ) {
    }

    public record DerivationResponse(
            String id,
            int versionNumber,
            String parentId,
            String source,
            String ruleVersion,
            List<Long> boundariesMs,
            List<ExclusionRequest> exclusions,
            boolean needsReview,
            String reviewReason,
            long createdAtMs,
            List<CycleResultResponse> cycles
    ) {
    }
}
