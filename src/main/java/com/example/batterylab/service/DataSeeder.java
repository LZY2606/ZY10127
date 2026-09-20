package com.example.batterylab.service;

import com.example.batterylab.domain.Models.CapabilityDefinition;
import com.example.batterylab.domain.Models.ProtocolDefinition;
import com.example.batterylab.domain.Models.ProtocolStep;
import com.example.batterylab.domain.Models.RuleDefinition;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DataSeeder implements ApplicationRunner {
    private static final long T0 = 1_700_000_000_000L;

    private final JdbcTemplate jdbc;
    private final JsonService json;

    public DataSeeder(JdbcTemplate jdbc, JsonService json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) {
        ProtocolDefinition protocol = new ProtocolDefinition(
                "两循环充放电演示协议",
                1000,
                2,
                List.of(
                        new ProtocolStep(0, "恒流充电", "CC_CHARGE", 3000, 2.0, 4.0, null, null),
                        new ProtocolStep(1, "恒压截止", "CV_CUTOFF", 3000, 2.0, 4.0, 0.5, null),
                        new ProtocolStep(2, "充电后静置", "REST", 2000, 0.0, null, null, null),
                        new ProtocolStep(3, "恒流放电", "CC_DISCHARGE", 3000, -2.0, 3.0, null, null),
                        new ProtocolStep(4, "放电后静置", "REST", 2000, 0.0, null, null, null),
                        new ProtocolStep(5, "循环跳转", "JUMP", 0, 0.0, null, null, 0)
                )
        );
        RuleDefinition ruleV1 = new RuleDefinition(
                "LEFT_RECTANGLE",
                "ASSIGN_ENDPOINT_TO_OUTGOING_STEP",
                "POSITIVE_IS_CHARGE",
                true,
                true
        );
        RuleDefinition ruleV2 = new RuleDefinition(
                "TRAPEZOID",
                "ZERO_CURRENT_AT_STEP_BOUNDARY",
                "POSITIVE_IS_CHARGE",
                true,
                true
        );
        CapabilityDefinition capability = new CapabilityDefinition(
                "cell-001", 0.0, 6.0, -5.0, 5.0, -10.0, 80.0, 1000
        );

        insertIfMissing("protocol_versions", "protocol-1", "protocol/v1", protocol.name(), json.write(protocol), T0);
        insertIfMissing("rule_versions", "rule-1", "rules/v1", "左矩形原始积分规则", json.write(ruleV1), T0);
        insertIfMissing("rule_versions", "rule-2", "rules/v2", "切分段梯形积分规则", json.write(ruleV2), T0 + 1);
        insertCapabilityIfMissing(capability, T0);
    }

    private void insertCapabilityIfMissing(CapabilityDefinition capability, long at) {
        Integer count = jdbc.queryForObject("select count(*) from device_capabilities where version=?",
                Integer.class, "capability/v1");
        if (count != null && count == 0) {
            jdbc.update("""
                    insert into device_capabilities(id, version, device_sn, content_json, created_at_ms)
                    values(?,?,?,?,?)
                    """, "capability-1", "capability/v1", capability.deviceSn(), json.write(capability), at);
        }
    }

    private void insertIfMissing(String table, String id, String version, String name, String content, long at) {
        Integer count = jdbc.queryForObject("select count(*) from " + table + " where version=?",
                Integer.class, version);
        if (count != null && count == 0) {
            jdbc.update("insert into " + table + "(id, version, name, content_json, created_at_ms) values(?,?,?,?,?)",
                    id, version, name, content, at);
        }
    }
}
