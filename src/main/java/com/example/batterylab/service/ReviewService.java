package com.example.batterylab.service;

import com.example.batterylab.repository.LabRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class ReviewService {
    private final LabRepository repository;
    private final DerivationService derivationService;

    public ReviewService(LabRepository repository, DerivationService derivationService) {
        this.repository = repository;
        this.derivationService = derivationService;
    }

    @Transactional
    public Map<String, Object> correctBoundary(String runId, int cycleIndex, long boundaryAtMs, String reason) {
        Map<String, Object> run = repository.getRun(runId);
        int limit = ((Number) run.get("cycle_limit")).intValue();
        if (cycleIndex < 1 || cycleIndex > limit) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cycle boundary index must be between 1 and cycle limit");
        }
        if (boundaryAtMs < 0 || (reason == null || reason.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Boundary timestamp and review reason are required");
        }
        long now = ((Number) run.get("virtual_time_ms")).longValue();
        repository.putBoundaryOverride(runId, cycleIndex, boundaryAtMs, reason, now);
        Map<String, Object> latest = repository.findLatestDerivation(runId).orElse(null);
        String parent = latest == null ? null : String.valueOf(latest.get("id"));
        String rule = latest == null ? null : String.valueOf(latest.get("rule_version"));
        return derivationService.createDerivation(runId, "BOUNDARY_CORRECTION", rule, parent,
                "Researcher corrected cycle " + cycleIndex + " boundary: " + reason);
    }

    @Transactional
    public Map<String, Object> exclude(String runId, long startAtMs, long endAtMs, String reason) {
        Map<String, Object> run = repository.getRun(runId);
        if (startAtMs < 0 || endAtMs <= startAtMs || reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Exclusion requires start < end and a reason");
        }
        long now = ((Number) run.get("virtual_time_ms")).longValue();
        repository.insertExclusion(UUID.randomUUID().toString(), runId, startAtMs, endAtMs, reason, now);
        Map<String, Object> latest = repository.findLatestDerivation(runId).orElse(null);
        String parent = latest == null ? null : String.valueOf(latest.get("id"));
        String rule = latest == null ? null : String.valueOf(latest.get("rule_version"));
        return derivationService.createDerivation(runId, "EXCLUSION", rule, parent,
                "Excluded sensor fault interval: " + reason);
    }

    @Transactional
    public Map<String, Object> recompute(String runId, String ruleVersion) {
        repository.getRun(runId);
        Map<String, Object> latest = repository.findLatestDerivation(runId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "A baseline derivation must exist first"));
        return derivationService.createDerivation(runId, "RECOMPUTE", ruleVersion,
                String.valueOf(latest.get("id")),
                "Researcher recomputed raw samples with rule " + ruleVersion);
    }
}
