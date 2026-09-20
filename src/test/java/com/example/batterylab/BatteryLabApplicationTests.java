package com.example.batterylab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BatteryLabApplicationTests {
    @DynamicPropertySource
    static void sqlite(DynamicPropertyRegistry registry) {
        Path db = Path.of("target/test-" + System.nanoTime() + ".db");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db.toAbsolutePath());
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper mapper;

    @Test
    void acknowledgmentWindowCrashDoesNotDuplicateSample() throws Exception {
        String run = createRun("rules/v2");
        JsonNode pending = post("/api/runs/" + run + "/simulator/advance", null);
        long seq = pending.get("sequence_number").asLong();
        assertThat(pending.get("status").asText()).isEqualTo("PENDING");

        post("/api/runs/" + run + "/device/offline", null);
        post("/api/runs/" + run + "/device/online", null);

        ResponseEntity<String> duplicateReport = postRaw("/api/runs/" + run + "/simulator/advance", null);
        assertThat(duplicateReport.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        JsonNode confirmed = post("/api/runs/" + run + "/samples/" + seq + "/confirm", null);
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
        JsonNode detail = detail(run);
        assertThat(detail.get("samples")).hasSize(1);
        assertThat(detail.get("samples").get(0).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(detail.get("virtual_time_ms").asLong()).isEqualTo(1000L);
    }

    @Test
    void duplicateSequenceWithDifferentPayloadEntersConflictInsteadOfOverwrite() {
        String run = createRun("rules/v2");
        post("/api/runs/" + run + "/simulator/advance", null);
        HttpHeaders headers = jsonHeaders();
        Map<String, Object> payload = Map.of(
                "sequenceNumber", 0,
                "sampledAtMs", 1000,
                "voltageV", 3.9,
                "currentA", -1.0,
                "temperatureC", 27.0
        );
        ResponseEntity<String> response = rest.exchange(
                "http://127.0.0.1:" + port + "/api/runs/" + run + "/samples",
                HttpMethod.POST, new HttpEntity<>(payload, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode detail = detail(run);
        assertThat(detail.get("state").asText()).isEqualTo("CONFLICT");
        assertThat(detail.get("samples").get(0).get("status").asText()).isEqualTo("CONFLICT");
        assertThat(detail.get("samples").get(0).get("voltage_v").asDouble()).isEqualTo(3.733333, org.assertj.core.data.Offset.offset(0.001));

        ResponseEntity<String> restart = postRaw("/api/runs/" + run + "/device/restart", null);
        assertThat(restart.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<String> cancel = postRaw("/api/runs/" + run + "/cancel", null);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(detail(run).get("state").asText()).isEqualTo("CONFLICT");
    }

    @Test
    void lateSampleDoesNotMovePublishedStepBackAndCreatesReviewDerivation() throws Exception {
        String run = createRun("rules/v2");
        advanceAndConfirm(run, 2);
        JsonNode before = detail(run);
        assertThat(before.get("current_step_index").asInt()).isEqualTo(0);
        assertThat(before.get("virtual_time_ms").asLong()).isEqualTo(2000L);

        HttpHeaders headers = jsonHeaders();
        Map<String, Object> late = Map.of(
                "sequenceNumber", 90,
                "sampledAtMs", 1,
                "voltageV", 3.61,
                "currentA", 1.0,
                "temperatureC", 25.0
        );
        rest.exchange("http://127.0.0.1:" + port + "/api/runs/" + run + "/samples",
                HttpMethod.POST, new HttpEntity<>(late, headers), String.class);
        post("/api/runs/" + run + "/samples/90/confirm", null);

        JsonNode after = detail(run);
        JsonNode lateSample = after.get("samples").get(0);
        for (JsonNode candidate : after.get("samples")) {
            if (candidate.get("sequence_number").asLong() == 90) {
                lateSample = candidate;
            }
        }
        assertThat(lateSample.get("status").asText()).isEqualTo("LATE");
        assertThat(after.get("current_step_index").asInt()).isZero();
        assertThat(after.get("virtual_time_ms").asLong()).isEqualTo(2000L);
        assertThat(after.get("derivations").get(0).get("needs_review").asBoolean()).isTrue();
        assertThat(after.get("derivations").get(0).get("source").asText()).isEqualTo("LATE_SAMPLE");
    }

    @Test
    void crossesCycleEndpointAndRecomputesWithSpecifiedOriginalRule() throws Exception {
        String run = createRun("rules/v1");
        int stepsPerCycle = 13;
        advanceAndConfirm(run, stepsPerCycle);
        JsonNode afterFirstCycle = detail(run);
        assertThat(afterFirstCycle.get("current_cycle").asInt()).isEqualTo(2);
        assertThat(afterFirstCycle.get("current_step_index").asInt()).isZero();
        assertThat(afterFirstCycle.get("derivedBoundaries").get(1).asLong()).isEqualTo(13000L);
        JsonNode firstDerivation = afterFirstCycle.get("derivations").get(0);
        assertThat(firstDerivation.get("rule_version").asText()).isEqualTo("rules/v1");
        assertThat(firstDerivation.get("cycles").get(0).get("charge_ah").asDouble())
                .isCloseTo(0.0025, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(firstDerivation.get("cycles").get(0).get("discharge_ah").asDouble())
                .isCloseTo(0.001666667, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(firstDerivation.get("cycles").get(0).get("coulombic_efficiency").asDouble())
                .isCloseTo(0.666666667, org.assertj.core.data.Offset.offset(0.001));

        advanceAndConfirm(run, stepsPerCycle);
        JsonNode completed = detail(run);
        assertThat(completed.get("state").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("interruption_reason").asText()).isEqualTo("COMPLETED");
        JsonNode completedDerivation = completed.get("derivations").get(completed.get("derivations").size() - 1);
        assertThat(completedDerivation.get("cycles")).hasSize(2);
        ResponseEntity<String> revive = postRaw("/api/runs/" + run + "/device/restart", null);
        assertThat(revive.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        JsonNode recomputed = post("/api/runs/" + run + "/recompute", Map.of("ruleVersion", "rules/v2"));
        assertThat(recomputed.get("rule_version").asText()).isEqualTo("rules/v2");
        JsonNode completedWithV2 = detail(run);
        JsonNode v2 = completedWithV2.get("derivations").get(completedWithV2.get("derivations").size() - 1);
        assertThat(v2.get("cycles").get(0).get("charge_ah").asDouble())
                .isCloseTo(0.001527778, org.assertj.core.data.Offset.offset(0.000001));
        assertThat(v2.get("cycles").get(0).get("discharge_ah").asDouble())
                .isCloseTo(0.001111111, org.assertj.core.data.Offset.offset(0.000001));
    }

    @Test
    void boundaryCorrectionAndSensorExclusionCreateImmutableDerivedVersions() throws Exception {
        String run = createRun("rules/v2");
        advanceAndConfirm(run, 13);
        JsonNode corrected = post("/api/runs/" + run + "/boundaries/1",
                Map.of("boundaryAtMs", 12000, "reason", "研究员根据电流归零修正"));
        assertThat(corrected.get("source").asText()).isEqualTo("BOUNDARY_CORRECTION");
        assertThat(corrected.get("needs_review").asBoolean()).isTrue();

        JsonNode excluded = post("/api/runs/" + run + "/exclusions",
                Map.of("startAtMs", 1000, "endAtMs", 3000, "reason", "温度传感器故障"));
        assertThat(excluded.get("source").asText()).isEqualTo("EXCLUSION");
        JsonNode detail = detail(run);
        JsonNode exclusionVersion = detail.get("derivations").get(detail.get("derivations").size() - 1);
        assertThat(exclusionVersion.get("cycles").get(0).get("excluded_ms").asLong()).isEqualTo(2000L);
        assertThat(detail.get("derivations")).hasSizeGreaterThanOrEqualTo(3);
        assertThat(detail.get("derivations").get(0).get("source").asText()).isEqualTo("CYCLE_BOUNDARY");
    }

    private String createRun(String ruleVersion) {
        JsonNode run = post("/api/runs", Map.of("ruleVersion", ruleVersion));
        return run.get("id").asText();
    }

    private void advanceAndConfirm(String run, int count) {
        for (int i = 0; i < count; i++) {
            JsonNode sample = post("/api/runs/" + run + "/simulator/advance", null);
            post("/api/runs/" + run + "/samples/" + sample.get("sequence_number").asLong() + "/confirm", null);
        }
    }

    private JsonNode detail(String run) {
        ResponseEntity<String> response = rest.getForEntity(
                "http://127.0.0.1:" + port + "/api/runs/" + run, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            return mapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode post(String path, Object body) {
        ResponseEntity<String> response = postRaw(path, body);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        if (response.getBody() == null || response.getBody().isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> postRaw(String path, Object body) {
        return rest.exchange("http://127.0.0.1:" + port + path, HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders()), String.class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
