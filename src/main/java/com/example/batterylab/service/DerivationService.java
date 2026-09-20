package com.example.batterylab.service;

import com.example.batterylab.domain.Models.RuleDefinition;
import com.example.batterylab.repository.LabRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DerivationService {
    private final LabRepository repository;
    private final JsonService json;
    private final CalculationService calculation;

    public DerivationService(LabRepository repository, JsonService json, CalculationService calculation) {
        this.repository = repository;
        this.json = json;
        this.calculation = calculation;
    }

    @Transactional
    public Map<String, Object> createDerivation(
            String runId,
            String source,
            String requestedRuleVersion,
            String parentId,
            String reviewReason
    ) {
        Map<String, Object> run = repository.getRun(runId);
        Map<String, Object> ruleVersion = requestedRuleVersion == null || requestedRuleVersion.isBlank()
                ? repository.findVersionById("rule_versions", string(run.get("rule_version_id")))
                : repository.findVersionByVersion("rule_versions", requestedRuleVersion);
        RuleDefinition rule = json.rule(string(ruleVersion.get("content_json")));

        List<Long> boundaries = boundaries(runId, run);
        List<Long> stepBoundaries = stepBoundaries(runId);
        List<CalculationService.Exclusion> exclusions = exclusions(runId);
        List<Map<String, Object>> samples = repository.listSamples(runId);
        List<CalculationService.CycleCalculation> cycles = calculation.calculate(
                samples, boundaries, stepBoundaries, exclusions, rule);

        int number = repository.nextDerivationNumber(runId);
        String derivationId = UUID.randomUUID().toString();
        long now = longValue(run.get("virtual_time_ms"));
        String id = string(ruleVersion.get("id"));
        String version = string(ruleVersion.get("version"));
        repository.insertDerivation(new Object[] {
                derivationId, runId, number, parentId, source, id, version,
                string(ruleVersion.get("content_json")),
                json.write(boundaries), json.write(serializableExclusions(exclusions)),
                reviewReason == null ? 0 : 1, reviewReason, now
        });
        for (CalculationService.CycleCalculation cycle : cycles) {
            repository.insertCycleResult(new Object[] {
                    UUID.randomUUID().toString(), derivationId, cycle.cycleIndex(), cycle.startMs(),
                    cycle.endMs(), cycle.chargeAh(), cycle.dischargeAh(), cycle.efficiency(),
                    cycle.sampleCount(), cycle.excludedMs()
            });
        }
        return repository.getDerivation(derivationId);
    }

    public List<Long> boundaries(String runId, Map<String, Object> run) {
        Map<Integer, Long> boundaries = new LinkedHashMap<>();
        long start = 0L;
        List<Map<String, Object>> events = repository.listEvents(runId);
        for (Map<String, Object> event : events) {
            if ("STEP_TRANSITION".equals(string(event.get("event_type")))
                    && "JUMP".equals(string(event.get("reason")))
                    && intValue(event.get("to_step_index")) == 0) {
                long at = longValue(event.get("at_ms"));
                int toCycle = intValue(event.get("to_cycle"));
                boundaries.putIfAbsent(toCycle - 1, at);
                start = Math.max(start, at);
            }
            if ("RUN_TERMINATED".equals(string(event.get("event_type")))
                    && "COMPLETED".equals(string(event.get("reason")))) {
                boundaries.putIfAbsent(intValue(run.get("cycle_limit")), longValue(event.get("at_ms")));
            }
        }
        for (Map<String, Object> override : repository.listBoundaryOverrides(runId)) {
            boundaries.put(intValue(override.get("cycle_index")), longValue(override.get("boundary_at_ms")));
        }
        List<Long> result = new ArrayList<>();
        result.add(0L);
        boundaries.entrySet().stream()
                .filter(entry -> entry.getKey() >= 1)
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .map(Map.Entry::getValue)
                .filter(at -> at >= result.get(0))
                .forEach(result::add);
        return result.stream().distinct().sorted().toList();
    }

    private List<Long> stepBoundaries(String runId) {
        return repository.listEvents(runId).stream()
                .filter(event -> "STEP_TRANSITION".equals(string(event.get("event_type"))))
                .map(event -> longValue(event.get("at_ms")))
                .distinct()
                .sorted()
                .toList();
    }

    private List<CalculationService.Exclusion> exclusions(String runId) {
        return repository.listExclusions(runId).stream()
                .map(row -> new CalculationService.Exclusion(
                        longValue(row.get("start_at_ms")),
                        longValue(row.get("end_at_ms")),
                        string(row.get("reason"))))
                .toList();
    }

    private List<Map<String, Object>> serializableExclusions(List<CalculationService.Exclusion> exclusions) {
        return exclusions.stream()
                .map(exclusion -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("startAtMs", exclusion.startMs());
                    map.put("endAtMs", exclusion.endMs());
                    map.put("reason", exclusion.reason());
                    return map;
                })
                .toList();
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
}
