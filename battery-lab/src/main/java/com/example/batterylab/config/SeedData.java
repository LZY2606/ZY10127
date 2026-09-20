package com.example.batterylab.config;

import com.example.batterylab.domain.DeviceProfile;
import com.example.batterylab.domain.ProtocolVersion;
import com.example.batterylab.domain.RuleVersion;
import com.example.batterylab.repo.DeviceProfileRepository;
import com.example.batterylab.repo.ProtocolVersionRepository;
import com.example.batterylab.repo.RuleVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Seeds independently versioned protocols, device capabilities and calculation rules. */
@Component
@Order(10)
public class SeedData implements ApplicationRunner {

    public static final String PROTOCOL_NAME = "lab-standard";
    public static final String PROTOCOL_VERSION = "1.0.0";
    public static final String PROFILE_NAME = "generic-channel";
    public static final String PROFILE_VERSION = "1.0.0";
    public static final String RULE_NAME = "capacity-rules";
    public static final String RULE_V1 = "1.0.0";
    public static final String RULE_V2 = "2.0.0";

    private final ProtocolVersionRepository protocolRepository;
    private final DeviceProfileRepository deviceProfileRepository;
    private final RuleVersionRepository ruleRepository;
    private final ObjectMapper objectMapper;

    public SeedData(ProtocolVersionRepository protocolRepository,
                    DeviceProfileRepository deviceProfileRepository,
                    RuleVersionRepository ruleRepository,
                    ObjectMapper objectMapper) {
        this.protocolRepository = protocolRepository;
        this.deviceProfileRepository = deviceProfileRepository;
        this.ruleRepository = ruleRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (protocolRepository.findByNameAndVersion(PROTOCOL_NAME, PROTOCOL_VERSION).isEmpty()) {
            protocolRepository.save(new ProtocolVersion(PROTOCOL_NAME, PROTOCOL_VERSION,
                    standardProtocolJson(), 0L));
        }
        if (deviceProfileRepository.findByNameAndVersion(PROFILE_NAME, PROFILE_VERSION).isEmpty()) {
            deviceProfileRepository.save(new DeviceProfile(PROFILE_NAME, PROFILE_VERSION,
                    capabilitiesJson(), 0L));
        }
        if (ruleRepository.findByNameAndVersion(RULE_NAME, RULE_V1).isEmpty()) {
            ruleRepository.save(new RuleVersion(RULE_NAME, RULE_V1, rulesJson(0), 0L));
        }
        if (ruleRepository.findByNameAndVersion(RULE_NAME, RULE_V2).isEmpty()) {
            ruleRepository.save(new RuleVersion(RULE_NAME, RULE_V2, rulesJson(150), 0L));
        }
    }

    private String standardProtocolJson() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode steps = root.putArray("steps");
        step(steps, "CC_CHARGE").put("currentMa", 2000).put("limitMv", 4200);
        step(steps, "CV_CUTOFF").put("currentMa", 100).put("limitMv", 4200);
        step(steps, "REST").put("durationMs", 600000);
        step(steps, "CC_DISCHARGE").put("currentMa", 2000).put("limitMv", 3000);
        step(steps, "REST").put("durationMs", 600000);
        step(steps, "LOOP").put("jumpTo", 1).put("repetitions", 3);
        return objectMapper.writeValueAsString(root);
    }

    private ObjectNode step(ArrayNode steps, String type) {
        ObjectNode s = steps.addObject();
        s.put("type", type);
        return s;
    }

    private String capabilitiesJson() throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("maxChargeCurrentMa", 5000);
        root.put("maxDischargeCurrentMa", 5000);
        root.put("voltageRangeMv", "2500..4500");
        root.put("samplePeriodMs", 60000);
        root.put("reports", objectMapper.createArrayNode()
                .add("voltageMv").add("currentMa").add("temperatureCd").add("direction"));
        return objectMapper.writeValueAsString(root);
    }

    /**
     * Rule versions differ only in the current dead-band, which makes rest/cutoff
     * micro-currents count as zero above the band; recomputing under v1 vs v2 gives
     * slightly different capacities, exercising "re-derive under a chosen old rule".
     */
    private String rulesJson(long deadbandMa) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("chargeSign", 1);
        root.put("endpointPolicy", "BOUNDARY_POINT_TO_PREVIOUS_STEP");
        root.put("integration", "TRAPEZOID");
        root.put("currentDeadbandMa", deadbandMa);
        root.put("minTemperatureCd", -400);
        root.put("maxTemperatureCd", 600);
        root.put("boundaryCycleStepIndex", 4);
        root.put("capacityUnit", "uAh");
        root.put("coulombicEfficiency", "dischargeUah/chargeUah, basis points");
        return objectMapper.writeValueAsString(root);
    }
}
