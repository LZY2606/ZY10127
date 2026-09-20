package com.example.batterylab.web;

public final class ApiDtos {
    private ApiDtos() {
    }

    public record CreateRunRequest(String protocolName, String protocolVersion,
                                   String deviceSerial, String profileName,
                                   String profileVersion, String ruleName,
                                   String ruleVersion) {
    }

    public record TickRequest(Long tickMs) {
    }

    public record SampleReportRequest(long seq, long tsMs, int voltageMv, int currentMa,
                                      int temperatureCd, int direction) {
    }

    public record ExclusionRequest(long fromTsMs, long toTsMs, String reason) {
    }

    public record BoundaryRequest(int cycleNo, long boundaryTsMs, String note) {
    }

    public record DeriveRequest(Long ruleVersionId, String reason) {
    }

    public record ResolveConflictRequest(String resolution) {
    }
}
