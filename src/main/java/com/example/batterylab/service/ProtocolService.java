package com.example.batterylab.service;

import com.example.batterylab.domain.Models.CapabilityDefinition;
import com.example.batterylab.domain.Models.InterruptionReason;
import com.example.batterylab.domain.Models.ProtocolDefinition;
import com.example.batterylab.domain.Models.ProtocolStep;
import com.example.batterylab.domain.Models.RuleDefinition;
import com.example.batterylab.domain.Models.RunState;
import com.example.batterylab.domain.Models.SampleStatus;
import com.example.batterylab.dto.Dtos.SampleRequest;
import com.example.batterylab.repository.LabRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProtocolService {
    private final LabRepository repository;
    private final JsonService json;
    private final DerivationService derivationService;

    public ProtocolService(LabRepository repository, JsonService json, DerivationService derivationService) {
        this.repository = repository;
        this.json = json;
        this.derivationService = derivationService;
    }

    @Transactional
    public Map<String, Object> createRun(String protocolVersion, String ruleVersion, String capabilityVersion) {
        Map<String, Object> protocolRow = repository.findVersionByVersion(
                "protocol_versions", orDefault(protocolVersion, "protocol/v1"));
        Map<String, Object> ruleRow = repository.findVersionByVersion(
                "rule_versions", orDefault(ruleVersion, "rules/v2"));
        Map<String, Object> capabilityRow = repository.findVersionByVersion(
                "device_capabilities", orDefault(capabilityVersion, "capability/v1"));
        ProtocolDefinition protocol = json.protocol(string(protocolRow.get("content_json")));
        RuleDefinition rule = json.rule(string(ruleRow.get("content_json")));
        CapabilityDefinition capability = json.capability(string(capabilityRow.get("content_json")));

        String runId = UUID.randomUUID().toString();
        long timeZero = 0L;
        repository.insertRun(new Object[] {
                runId, capability.deviceSn(), string(protocolRow.get("id")),
                string(protocolRow.get("content_json")), string(ruleRow.get("id")),
                string(ruleRow.get("content_json")), string(capabilityRow.get("id")),
                string(capabilityRow.get("content_json")), RunState.RUNNING.name(),
                InterruptionReason.NONE.name(), 0, 1, protocol.cycleLimit(), timeZero, 0,
                timeZero, null, 3.6, 0.0, 25.0, timeZero, null
        });
        repository.insertDevice(runId, capability.deviceSn(), timeZero);
        repository.insertEvent(event(runId, "RUN_CREATED", timeZero, 0, 0, 1, 1,
                null, RunState.RUNNING.name(), null, null));
        return repository.getRun(runId);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> advance(String runId) {
        Map<String, Object> run = activeRun(runId, RunState.RUNNING);
        Map<String, Object> device = repository.getDevice(runId);
        requireOnline(device);
        if (repository.findPendingSample(runId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "An unconfirmed sample is still in the acknowledgment window");
        }
        ProtocolDefinition protocol = protocol(run);
        int stepIndex = intValue(run.get("current_step_index"));
        int cycle = intValue(run.get("current_cycle"));
        ProtocolStep step = protocol.steps().get(stepIndex);
        if (repository.findPendingSample(runId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "An unconfirmed sample is still in the acknowledgment window");
        }
        long nextSeq = repository.listSamples(runId).stream()
                .mapToLong(sample -> longValue(sample.get("sequence_number")))
                .max().orElse(-1L) + 1;
        long sampleTime = lastTime(run) + protocol.sampleIntervalMs();
        long elapsedBefore = intValue(run.get("step_elapsed_ms")) + protocol.sampleIntervalMs();
        double[] physics = simulate(step, elapsedBefore, sampleTime, cycle);
        double voltage = physics[0];
        double current = physics[1];
        double temperature = physics[2];
        SampleRequest request = new SampleRequest(nextSeq, sampleTime, voltage, current, temperature);
        validateCapability(request, capability(run));
        Map<String, Object> sample = insertReportedSample(run, request, sampleTime);
        return sample;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> reportSample(String runId, SampleRequest request) {
        Map<String, Object> run = activeRun(runId, RunState.RUNNING);
        Map<String, Object> device = repository.getDevice(runId);
        requireOnline(device);
        validateCapability(request, capability(run));
        Optional<Map<String, Object>> pending = repository.findPendingSample(runId);
        if (pending.isPresent()
                && longValue(pending.get().get("sequence_number")) != request.sequenceNumber()) {
            throw new ApiException(HttpStatus.CONFLICT, "An unconfirmed sample is still in the acknowledgment window");
        }
        return insertReportedSample(run, request, longValue(run.get("virtual_time_ms")));
    }

    @Transactional
    public Map<String, Object> confirm(String runId, long sequenceNumber) {
        Map<String, Object> run = repository.getRun(runId);
        if (RunState.CONFLICT == state(run)) {
            throw new ApiException(HttpStatus.CONFLICT, "Run is in terminal conflict state");
        }
        if (RunState.RUNNING != state(run)) {
            throw new ApiException(HttpStatus.CONFLICT, "Only a running run can confirm a sample");
        }
        Map<String, Object> device = repository.getDevice(runId);
        requireOnline(device);
        Map<String, Object> sample = repository.findSample(runId, string(run.get("device_sn")), sequenceNumber)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Unknown sample sequence"));
        if (SampleStatus.CONFLICT == sampleStatus(sample)) {
            throw new ApiException(HttpStatus.CONFLICT, "Sample sequence has conflicting payloads");
        }
        if (SampleStatus.CONFIRMED == sampleStatus(sample) || SampleStatus.LATE == sampleStatus(sample)) {
            return sample;
        }
        long sampleTime = longValue(sample.get("sampled_at_ms"));
        long lastConfirmed = run.get("last_confirmed_time_ms") == null
                ? -1L : longValue(run.get("last_confirmed_time_ms"));
        long at = Math.max(sampleTime, longValue(run.get("virtual_time_ms")));
        if (sampleTime <= lastConfirmed) {
            repository.updateSampleStatus(string(sample.get("id")), SampleStatus.LATE, at,
                    repository.findLatestDerivation(runId).map(row -> string(row.get("id"))).orElse(null));
            Map<String, Object> latest = repository.findLatestDerivation(runId).orElse(null);
            String parent = latest == null ? null : string(latest.get("id"));
            derivationService.createDerivation(runId, "LATE_SAMPLE",
                    null, parent,
                    "Late confirmed sample at t=" + sampleTime + " ms; published steps did not move backward");
            return sample;
        }

        ProtocolDefinition protocol = protocol(run);
        ProtocolStep step = protocol.steps().get(intValue(run.get("current_step_index")));
        int oldStep = intValue(run.get("current_step_index"));
        int oldCycle = intValue(run.get("current_cycle"));
        int nextStep = oldStep;
        int nextCycle = oldCycle;
        boolean crossing = crosses(step, sample, longValue(run.get("voltage_v")));
        boolean durationComplete = intValue(run.get("step_elapsed_ms")) + protocol.sampleIntervalMs() >= step.durationMs();
        boolean atJumpBoundary = durationComplete && oldStep + 1 < protocol.steps().size()
                && "JUMP".equals(protocol.steps().get(oldStep + 1).type());
        String transitionReason = null;
        if (crossing) {
            transitionReason = thresholdReason(step);
        } else if (durationComplete) {
            transitionReason = atJumpBoundary ? "JUMP" : "DURATION";
        }
        String completionReason = null;
        if (transitionReason != null) {
            if (atJumpBoundary) {
                nextStep = protocol.steps().get(oldStep + 1).jumpTo() == null
                        ? 0 : protocol.steps().get(oldStep + 1).jumpTo();
                nextCycle = oldCycle + 1;
                if (nextCycle > protocol.cycleLimit()) {
                    completionReason = "COMPLETED";
                }
            } else {
                nextStep = oldStep + 1;
                if (nextStep >= protocol.steps().size()) {
                    completionReason = "COMPLETED";
                }
            }
        }

        repository.updateSampleStatus(string(sample.get("id")), SampleStatus.CONFIRMED, at, null);
        int elapsed = transitionReason == null
                ? intValue(run.get("step_elapsed_ms")) + (int) protocol.sampleIntervalMs()
                : 0;
        RunState nextState = RunState.RUNNING;
        InterruptionReason interruption = InterruptionReason.NONE;
        Long endedAt = null;
        if (completionReason != null) {
            nextState = RunState.COMPLETED;
            interruption = InterruptionReason.COMPLETED;
            endedAt = at;
        }
        if (transitionReason != null) {
            if (atJumpBoundary && completionReason == null) {
                repository.insertEvent(event(runId, "STEP_TRANSITION", sampleTime, oldStep + 1, nextStep,
                        oldCycle, nextCycle, RunState.RUNNING.name(), RunState.RUNNING.name(),
                        "JUMP", null));
            } else {
                repository.insertEvent(event(runId, "STEP_TRANSITION", sampleTime, oldStep,
                        completionReason != null ? oldStep : nextStep, oldCycle,
                        completionReason != null ? oldCycle : nextCycle,
                        RunState.RUNNING.name(), nextState.name(), transitionReason, null));
            }
        }
        if (completionReason != null) {
            repository.insertEvent(event(runId, "RUN_TERMINATED", sampleTime, oldStep, oldStep,
                    oldCycle, oldCycle, RunState.RUNNING.name(), RunState.COMPLETED.name(),
                    "COMPLETED", null));
        }
        repository.updateRunHead(new Object[] {
                nextState.name(), interruption.name(),
                completionReason != null ? oldStep : nextStep,
                completionReason != null ? oldCycle : nextCycle,
                sampleTime, completionReason != null ? 0 : elapsed, sampleTime, sampleTime,
                number(sample.get("voltage_v")), number(sample.get("current_a")),
                number(sample.get("temperature_c")), endedAt, runId
        });
        if (completionReason != null || atJumpBoundary) {
            Map<String, Object> latest = repository.findLatestDerivation(runId).orElse(null);
            String parent = latest == null ? null : string(latest.get("id"));
            derivationService.createDerivation(runId,
                    completionReason != null ? "COMPLETED" : "CYCLE_BOUNDARY",
                    null, parent,
                    completionReason != null ? null : "Review cycle boundary and interrupt reason");
        }
        return repository.findSample(runId, string(run.get("device_sn")), sequenceNumber).orElseThrow();
    }

    @Transactional
    public Map<String, Object> pause(String runId) {
        return transitionRecoverable(runId, RunState.PAUSED, InterruptionReason.PAUSED, "PAUSED");
    }

    @Transactional
    public Map<String, Object> resume(String runId) {
        Map<String, Object> run = repository.getRun(runId);
        if (RunState.PAUSED != state(run)) {
            throw new ApiException(HttpStatus.CONFLICT, "Only a paused run can resume");
        }
        requireOnline(repository.getDevice(runId));
        long at = longValue(run.get("virtual_time_ms"));
        repository.insertEvent(event(runId, "RUN_RESUMED", at,
                intValue(run.get("current_step_index")), intValue(run.get("current_step_index")),
                intValue(run.get("current_cycle")), intValue(run.get("current_cycle")),
                RunState.PAUSED.name(), RunState.RUNNING.name(), null, null));
        repository.updateRunHead(new Object[] {
                RunState.RUNNING.name(), InterruptionReason.NONE.name(),
                intValue(run.get("current_step_index")), intValue(run.get("current_cycle")),
                longValue(run.get("entered_at_ms")), intValue(run.get("step_elapsed_ms")),
                longValue(run.get("virtual_time_ms")), run.get("last_confirmed_time_ms") == null
                        ? null : longValue(run.get("last_confirmed_time_ms")),
                number(run.get("voltage_v")), number(run.get("current_a")),
                number(run.get("temperature_c")), null, runId
        });
        return repository.getRun(runId);
    }

    @Transactional
    public Map<String, Object> cancel(String runId) {
        Map<String, Object> run = repository.getRun(runId);
        RunState current = state(run);
        if (current == RunState.COMPLETED || current == RunState.CANCELLED
                || current == RunState.DEVICE_FAULT || current == RunState.CONFLICT) {
            throw new ApiException(HttpStatus.CONFLICT, "Terminal run cannot be cancelled");
        }
        return terminal(runId, RunState.CANCELLED, InterruptionReason.CANCELLED, "CANCELLED");
    }

    @Transactional
    public Map<String, Object> deviceFault(String runId) {
        Map<String, Object> run = repository.getRun(runId);
        if (isTerminal(state(run))) {
            throw new ApiException(HttpStatus.CONFLICT, "Terminal run cannot enter another terminal state");
        }
        repository.updateDevice(runId, false, false, longValue(run.get("virtual_time_ms")));
        return terminal(runId, RunState.DEVICE_FAULT, InterruptionReason.DEVICE_FAULT, "DEVICE_FAULT");
    }

    @Transactional
    public Map<String, Object> disconnect(String runId) {
        Map<String, Object> run = repository.getRun(runId);
        if (isTerminal(state(run))) {
            throw new ApiException(HttpStatus.CONFLICT, "Terminal run cannot change device connectivity");
        }
        long at = longValue(run.get("virtual_time_ms"));
        repository.updateDevice(runId, false, false, at);
        repository.insertEvent(event(runId, "DEVICE_OFFLINE", at,
                intValue(run.get("current_step_index")), intValue(run.get("current_step_index")),
                intValue(run.get("current_cycle")), intValue(run.get("current_cycle")),
                state(run).name(), state(run).name(), "OFFLINE", null));
        return repository.getDevice(runId);
    }

    @Transactional
    public Map<String, Object> reconnect(String runId) {
        Map<String, Object> run = repository.getRun(runId);
        if (isTerminal(state(run))) {
            throw new ApiException(HttpStatus.CONFLICT, "Terminal run cannot be reconnected");
        }
        long at = longValue(run.get("virtual_time_ms"));
        repository.updateDevice(runId, true, true, at);
        repository.insertEvent(event(runId, "DEVICE_ONLINE", at,
                intValue(run.get("current_step_index")), intValue(run.get("current_step_index")),
                intValue(run.get("current_cycle")), intValue(run.get("current_cycle")),
                state(run).name(), state(run).name(), "RECONNECTED", null));
        return repository.getDevice(runId);
    }

    @Transactional
    public Map<String, Object> restartDevice(String runId) {
        Map<String, Object> run = repository.getRun(runId);
        RunState current = state(run);
        if (current != RunState.RUNNING && current != RunState.PAUSED) {
            throw new ApiException(HttpStatus.CONFLICT, "Only recoverable runs can restart their device");
        }
        long at = longValue(run.get("virtual_time_ms"));
        repository.updateDevice(runId, true, true, at);
        repository.insertEvent(event(runId, "DEVICE_RESTARTED", at,
                intValue(run.get("current_step_index")), intValue(run.get("current_step_index")),
                intValue(run.get("current_cycle")), intValue(run.get("current_cycle")),
                current.name(), current.name(), "RESTARTED", null));
        return repository.getDevice(runId);
    }

    private Map<String, Object> transitionRecoverable(
            String runId, RunState target, InterruptionReason reason, String eventReason
    ) {
        Map<String, Object> run = repository.getRun(runId);
        if (state(run) != RunState.RUNNING) {
            throw new ApiException(HttpStatus.CONFLICT, "Only a running run can enter " + target);
        }
        long at = longValue(run.get("virtual_time_ms"));
        repository.insertEvent(event(runId, "RUN_PAUSED", at,
                intValue(run.get("current_step_index")), intValue(run.get("current_step_index")),
                intValue(run.get("current_cycle")), intValue(run.get("current_cycle")),
                RunState.RUNNING.name(), target.name(), eventReason, null));
        repository.updateRunHead(new Object[] {
                target.name(), reason.name(), intValue(run.get("current_step_index")),
                intValue(run.get("current_cycle")), longValue(run.get("entered_at_ms")),
                intValue(run.get("step_elapsed_ms")), longValue(run.get("virtual_time_ms")),
                run.get("last_confirmed_time_ms") == null ? null
                        : longValue(run.get("last_confirmed_time_ms")),
                number(run.get("voltage_v")), number(run.get("current_a")),
                number(run.get("temperature_c")), null, runId
        });
        return repository.getRun(runId);
    }

    private Map<String, Object> terminal(
            String runId, RunState target, InterruptionReason reason, String eventReason
    ) {
        Map<String, Object> run = repository.getRun(runId);
        long at = longValue(run.get("virtual_time_ms"));
        repository.insertEvent(event(runId, "RUN_TERMINATED", at,
                intValue(run.get("current_step_index")), intValue(run.get("current_step_index")),
                intValue(run.get("current_cycle")), intValue(run.get("current_cycle")),
                state(run).name(), target.name(), eventReason, null));
        repository.updateRunHead(new Object[] {
                target.name(), reason.name(), intValue(run.get("current_step_index")),
                intValue(run.get("current_cycle")), longValue(run.get("entered_at_ms")),
                intValue(run.get("step_elapsed_ms")), longValue(run.get("virtual_time_ms")),
                run.get("last_confirmed_time_ms") == null ? null
                        : longValue(run.get("last_confirmed_time_ms")),
                number(run.get("voltage_v")), number(run.get("current_a")),
                number(run.get("temperature_c")), at, runId
        });
        return repository.getRun(runId);
    }

    private Map<String, Object> insertReportedSample(
            Map<String, Object> run, SampleRequest request, long now
    ) {
        String runId = string(run.get("id"));
        String deviceSn = string(run.get("device_sn"));
        String payload = canonicalPayload(deviceSn, request);
        String hash = sha256(payload);
        var existing = repository.findSample(runId, deviceSn, request.sequenceNumber());
        if (existing.isPresent()) {
            Map<String, Object> old = existing.get();
            if (hash.equals(string(old.get("payload_hash")))) {
                return old;
            }
            if (SampleStatus.CONFLICT != sampleStatus(old)) {
                repository.updateSampleStatus(string(old.get("id")), SampleStatus.CONFLICT, null, null);
            }
            repository.insertConflictAttempt(UUID.randomUUID().toString(), string(old.get("id")),
                    payload, hash, now);
            markConflictTerminal(run, now);
            throw new ApiException(HttpStatus.CONFLICT,
                    "Duplicate sequence " + request.sequenceNumber() + " carried a different payload");
        }

        String sampleId = UUID.randomUUID().toString();
        repository.insertSample(new Object[] {
                sampleId, runId, deviceSn, request.sequenceNumber(), request.sampledAtMs(),
                request.voltageV(), request.currentA(), request.temperatureC(), hash,
                SampleStatus.PENDING.name(), now, null, null
        });
        return repository.findSample(runId, deviceSn, request.sequenceNumber()).orElseThrow();
    }

    private void markConflictTerminal(Map<String, Object> run, long at) {
        String runId = string(run.get("id"));
        if (state(run) == RunState.CONFLICT) {
            return;
        }
        repository.insertEvent(event(runId, "RUN_TERMINATED", at,
                intValue(run.get("current_step_index")), intValue(run.get("current_step_index")),
                intValue(run.get("current_cycle")), intValue(run.get("current_cycle")),
                state(run).name(), RunState.CONFLICT.name(), "CONFLICT", null));
        repository.updateRunHead(new Object[] {
                RunState.CONFLICT.name(), InterruptionReason.CONFLICT.name(),
                intValue(run.get("current_step_index")), intValue(run.get("current_cycle")),
                longValue(run.get("entered_at_ms")), intValue(run.get("step_elapsed_ms")),
                longValue(run.get("virtual_time_ms")),
                run.get("last_confirmed_time_ms") == null ? null
                        : longValue(run.get("last_confirmed_time_ms")),
                number(run.get("voltage_v")), number(run.get("current_a")),
                number(run.get("temperature_c")), at, runId
        });
    }

    private double[] simulate(ProtocolStep step, long elapsed, long t, int cycle) {
        double voltage = switch (step.type()) {
            case "CC_CHARGE" -> 3.6 + 0.4 * elapsed / step.durationMs();
            case "CV_CUTOFF" -> 4.0;
            case "CC_DISCHARGE" -> 3.9 - 0.9 * elapsed / step.durationMs();
            default -> step.index() == 2
                    ? 4.0 - 0.05 * elapsed / step.durationMs()
                    : 3.0 + 0.6 * elapsed / step.durationMs();
        };
        double current = switch (step.type()) {
            case "CC_CHARGE", "CC_DISCHARGE" -> step.currentA();
            case "CV_CUTOFF" -> 2.0 - 1.5 * elapsed / step.durationMs();
            default -> 0.0;
        };
        double temperature = 25.0 + ((t / 1000) % 5) + cycle * 0.25;
        return new double[] {round(voltage, 6), round(current, 6), round(temperature, 6)};
    }

    private boolean crosses(ProtocolStep step, Map<String, Object> sample, double previousVoltage) {
        double voltage = number(sample.get("voltage_v"));
        double current = number(sample.get("current_a"));
        return switch (step.type()) {
            case "CC_CHARGE" -> step.targetVoltageV() != null && voltage >= step.targetVoltageV();
            case "CC_DISCHARGE" -> step.targetVoltageV() != null && voltage <= step.targetVoltageV();
            case "CV_CUTOFF" -> step.cutoffCurrentA() != null && current <= step.cutoffCurrentA();
            default -> false;
        };
    }

    private String thresholdReason(ProtocolStep step) {
        return switch (step.type()) {
            case "CC_CHARGE" -> "VOLTAGE_THRESHOLD";
            case "CC_DISCHARGE" -> "VOLTAGE_THRESHOLD";
            case "CV_CUTOFF" -> "CURRENT_CUTOFF";
            default -> "DURATION";
        };
    }

    private void validateCapability(SampleRequest request, CapabilityDefinition capability) {
        if (request.sampledAtMs() < 0 || request.sequenceNumber() < 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Negative sequence or virtual timestamp");
        }
        if (request.voltageV() < capability.minVoltageV() || request.voltageV() > capability.maxVoltageV()
                || request.currentA() < capability.minCurrentA() || request.currentA() > capability.maxCurrentA()
                || request.temperatureC() < capability.minTemperatureC()
                || request.temperatureC() > capability.maxTemperatureC()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Sample exceeds device capability envelope");
        }
    }

    private Map<String, Object> activeRun(String runId, RunState required) {
        Map<String, Object> run = repository.getRun(runId);
        if (state(run) != required) {
            throw new ApiException(HttpStatus.CONFLICT, "Run is " + state(run) + ", expected " + required);
        }
        return run;
    }

    private void requireOnline(Map<String, Object> device) {
        if (intValue(device.get("online")) != 1 || intValue(device.get("connected")) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "Device is offline");
        }
    }

    private ProtocolDefinition protocol(Map<String, Object> run) {
        return json.protocol(string(run.get("protocol_json")));
    }

    private CapabilityDefinition capability(Map<String, Object> run) {
        return json.capability(string(run.get("capability_json")));
    }

    private Object ruleVersionId(Map<String, Object> run) {
        return run.get("rule_version_id");
    }

    private long lastTime(Map<String, Object> run) {
        return run.get("last_confirmed_time_ms") == null
                ? 0L : longValue(run.get("last_confirmed_time_ms"));
    }

    private Object[] event(
            String runId,
            String type,
            long at,
            int fromStep,
            int toStep,
            int fromCycle,
            int toCycle,
            String fromState,
            String toState,
            String reason,
            String detail
    ) {
        return new Object[] {
                UUID.randomUUID().toString(), runId, type, at, fromStep, toStep, fromCycle, toCycle,
                fromState, toState, reason, detail
        };
    }

    private RunState state(Map<String, Object> run) {
        return RunState.valueOf(string(run.get("state")));
    }

    private SampleStatus sampleStatus(Map<String, Object> sample) {
        return SampleStatus.valueOf(string(sample.get("status")));
    }

    private boolean isTerminal(RunState state) {
        return state == RunState.COMPLETED || state == RunState.CANCELLED
                || state == RunState.DEVICE_FAULT || state == RunState.CONFLICT;
    }

    private String canonicalPayload(String deviceSn, SampleRequest request) {
        return deviceSn + "|" + request.sequenceNumber() + "|" + request.sampledAtMs()
                + "|" + request.voltageV() + "|" + request.currentA() + "|" + request.temperatureC();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private double round(double value, int digits) {
        return java.math.BigDecimal.valueOf(value)
                .setScale(digits, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private long longValue(Object value) {
        return ((Number) value).longValue();
    }

    private int intValue(Object value) {
        return ((Number) value).intValue();
    }

    private double number(Object value) {
        return ((Number) value).doubleValue();
    }
}
