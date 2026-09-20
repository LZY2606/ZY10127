package com.example.batterylab.service;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Versioned calculation rules.
 *
 * @param chargeSign             sign convention for charging current (+1 or -1)
 * @param endpointPolicy         how a point exactly on a step boundary is assigned
 * @param integration            integration method (only TRAPEZOID supported)
 * @param currentDeadbandMa      |current| at/below this counts as zero
 * @param minTemperatureCd       inclusive lower temperature bound
 * @param maxTemperatureCd       inclusive upper temperature bound
 * @param boundaryCycleStepIndex zero-based step index that closes a cycle (discharge end),
 *                               or -1 if the whole protocol equals one cycle
 */
public record RuleSpec(int chargeSign, String endpointPolicy, String integration,
                       long currentDeadbandMa, int minTemperatureCd, int maxTemperatureCd,
                       int boundaryCycleStepIndex) {

    public static final String ENDPOINT_TO_PREVIOUS = "BOUNDARY_POINT_TO_PREVIOUS_STEP";

    public static RuleSpec parse(JsonNode root) {
        int chargeSign = root.path("chargeSign").asInt(1);
        if (chargeSign != 1 && chargeSign != -1) {
            throw new IllegalArgumentException("chargeSign must be +1 or -1");
        }
        String endpointPolicy = root.path("endpointPolicy")
                .asText(ENDPOINT_TO_PREVIOUS);
        String integration = root.path("integration").asText("TRAPEZOID");
        if (!"TRAPEZOID".equals(integration)) {
            throw new IllegalArgumentException("unsupported integration: " + integration);
        }
        long deadband = root.path("currentDeadbandMa").asLong(0L);
        int minT = root.path("minTemperatureCd").asInt(-400);
        int maxT = root.path("maxTemperatureCd").asInt(600);
        int boundary = root.path("boundaryCycleStepIndex").asInt(-1);
        return new RuleSpec(chargeSign, endpointPolicy, integration, deadband, minT, maxT, boundary);
    }

    /** Effective charge-direction milliamps under this version's sign convention. */
    public int effectiveChargeCurrent(int rawCurrentMa, int direction) {
        if (Math.abs(rawCurrentMa) <= currentDeadbandMa || direction == 0) {
            return 0;
        }
        int sign = rawCurrentMa >= 0 ? 1 : -1;
        return sign == chargeSign ? Math.abs(rawCurrentMa) : -Math.abs(rawCurrentMa);
    }
}
