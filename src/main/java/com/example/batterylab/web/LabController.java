package com.example.batterylab.web;

import com.example.batterylab.dto.Dtos.BoundaryRequest;
import com.example.batterylab.dto.Dtos.CreateRunRequest;
import com.example.batterylab.dto.Dtos.ExclusionRequest;
import com.example.batterylab.dto.Dtos.RecomputeRequest;
import com.example.batterylab.dto.Dtos.SampleRequest;
import com.example.batterylab.repository.LabRepository;
import com.example.batterylab.service.DerivationService;
import com.example.batterylab.service.ProtocolService;
import com.example.batterylab.service.ReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LabController {
    private final LabRepository repository;
    private final ProtocolService protocolService;
    private final ReviewService reviewService;
    private final DerivationService derivationService;

    public LabController(
            LabRepository repository,
            ProtocolService protocolService,
            ReviewService reviewService,
            DerivationService derivationService
    ) {
        this.repository = repository;
        this.protocolService = protocolService;
        this.reviewService = reviewService;
        this.derivationService = derivationService;
    }

    @GetMapping("/versions")
    public Map<String, Object> versions() {
        return Map.of(
                "protocols", repository.listVersions("protocol_versions"),
                "rules", repository.listVersions("rule_versions"),
                "capabilities", repository.listVersions("device_capabilities")
        );
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs() {
        return repository.listRuns();
    }

    @PostMapping("/runs")
    public Map<String, Object> createRun(@RequestBody(required = false) CreateRunRequest request) {
        String protocol = request == null ? null : request.protocolVersion();
        String rule = request == null ? null : request.ruleVersion();
        String capability = request == null ? null : request.capabilityVersion();
        return protocolService.createRun(protocol, rule, capability);
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> run(@PathVariable String runId) {
        return detail(runId);
    }

    @PostMapping("/runs/{runId}/simulator/advance")
    public Map<String, Object> advance(@PathVariable String runId) {
        return protocolService.advance(runId);
    }

    @PostMapping("/runs/{runId}/samples")
    public Map<String, Object> report(@PathVariable String runId, @RequestBody SampleRequest request) {
        return protocolService.reportSample(runId, request);
    }

    @PostMapping("/runs/{runId}/samples/{sequenceNumber}/confirm")
    public Map<String, Object> confirm(@PathVariable String runId, @PathVariable long sequenceNumber) {
        return protocolService.confirm(runId, sequenceNumber);
    }

    @PostMapping("/runs/{runId}/pause")
    public Map<String, Object> pause(@PathVariable String runId) {
        return protocolService.pause(runId);
    }

    @PostMapping("/runs/{runId}/resume")
    public Map<String, Object> resume(@PathVariable String runId) {
        return protocolService.resume(runId);
    }

    @PostMapping("/runs/{runId}/cancel")
    public Map<String, Object> cancel(@PathVariable String runId) {
        return protocolService.cancel(runId);
    }

    @PostMapping("/runs/{runId}/device/offline")
    public Map<String, Object> offline(@PathVariable String runId) {
        return protocolService.disconnect(runId);
    }

    @PostMapping("/runs/{runId}/device/online")
    public Map<String, Object> online(@PathVariable String runId) {
        return protocolService.reconnect(runId);
    }

    @PostMapping("/runs/{runId}/device/restart")
    public Map<String, Object> restart(@PathVariable String runId) {
        return protocolService.restartDevice(runId);
    }

    @PostMapping("/runs/{runId}/device/fault")
    public Map<String, Object> fault(@PathVariable String runId) {
        return protocolService.deviceFault(runId);
    }

    @PostMapping("/runs/{runId}/boundaries/{cycleIndex}")
    public Map<String, Object> boundary(
            @PathVariable String runId,
            @PathVariable int cycleIndex,
            @RequestBody BoundaryRequest request
    ) {
        return reviewService.correctBoundary(runId, cycleIndex, request.boundaryAtMs(), request.reason());
    }

    @PostMapping("/runs/{runId}/exclusions")
    public Map<String, Object> exclude(@PathVariable String runId, @RequestBody ExclusionRequest request) {
        return reviewService.exclude(runId, request.startAtMs(), request.endAtMs(), request.reason());
    }

    @PostMapping("/runs/{runId}/recompute")
    public Map<String, Object> recompute(@PathVariable String runId,
                                         @RequestBody(required = false) RecomputeRequest request) {
        return reviewService.recompute(runId, request == null ? null : request.ruleVersion());
    }

    private Map<String, Object> detail(String runId) {
        Map<String, Object> run = repository.getRun(runId);
        Map<String, Object> result = new LinkedHashMap<>(run);
        result.put("device", repository.getDevice(runId));
        result.put("samples", repository.listSamples(runId));
        result.put("events", repository.listEvents(runId));
        result.put("boundaryOverrides", repository.listBoundaryOverrides(runId));
        result.put("exclusions", repository.listExclusions(runId));
        result.put("derivedBoundaries", derivationService.boundaries(runId, run));
        List<Map<String, Object>> derivations = repository.listDerivations(runId);
        List<Map<String, Object>> withCycles = derivations.stream().map(derivation -> {
            Map<String, Object> row = new LinkedHashMap<>(derivation);
            row.put("cycles", repository.listCycleResults(String.valueOf(derivation.get("id"))));
            return row;
        }).toList();
        result.put("derivations", withCycles);
        return result;
    }
}
