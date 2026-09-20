package com.example.batterylab.web;

import com.example.batterylab.domain.*;
import com.example.batterylab.repo.*;
import com.example.batterylab.service.*;
import com.example.batterylab.sim.TickResult;
import com.example.batterylab.web.ApiDtos.*;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runService;
    private final IngestionService ingestionService;
    private final ConflictService conflictService;
    private final ReviewService reviewService;
    private final DerivationService derivationService;
    private final RunRepository runRepository;
    private final SamplePointRepository samplePointRepository;
    private final SampleConflictRepository conflictRepository;
    private final PointExclusionRepository exclusionRepository;
    private final BoundaryOverrideRepository boundaryOverrideRepository;
    private final StepExecRepository stepExecRepository;
    private final DerivedVersionRepository derivedVersionRepository;
    private final CycleResultRepository cycleResultRepository;
    private final PointAmendmentRepository amendmentRepository;

    public RunController(RunService runService, IngestionService ingestionService,
                         ConflictService conflictService, ReviewService reviewService,
                         DerivationService derivationService, RunRepository runRepository,
                         SamplePointRepository samplePointRepository,
                         SampleConflictRepository conflictRepository,
                         PointExclusionRepository exclusionRepository,
                         BoundaryOverrideRepository boundaryOverrideRepository,
                         StepExecRepository stepExecRepository,
                         DerivedVersionRepository derivedVersionRepository,
                         CycleResultRepository cycleResultRepository,
                         PointAmendmentRepository amendmentRepository) {
        this.runService = runService;
        this.ingestionService = ingestionService;
        this.conflictService = conflictService;
        this.reviewService = reviewService;
        this.derivationService = derivationService;
        this.runRepository = runRepository;
        this.samplePointRepository = samplePointRepository;
        this.conflictRepository = conflictRepository;
        this.exclusionRepository = exclusionRepository;
        this.boundaryOverrideRepository = boundaryOverrideRepository;
        this.stepExecRepository = stepExecRepository;
        this.derivedVersionRepository = derivedVersionRepository;
        this.cycleResultRepository = cycleResultRepository;
        this.amendmentRepository = amendmentRepository;
    }

    @GetMapping
    public List<Run> list() {
        return runRepository.findAllByOrderByIdDesc();
    }

    @PostMapping
    public Run create(@RequestBody CreateRunRequest req) {
        return runService.createRun(
                orDefault(req.protocolName(), "lab-standard"),
                orDefault(req.protocolVersion(), "1.0.0"),
                orDefault(req.deviceSerial(), "DEV-0001"),
                orDefault(req.profileName(), "generic-channel"),
                orDefault(req.profileVersion(), "1.0.0"),
                orDefault(req.ruleName(), "capacity-rules"),
                orDefault(req.ruleVersion(), "1.0.0"));
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        Run run = runRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown run " + id));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", run);
        out.put("steps", stepExecRepository.findByRunIdOrderByIdAsc(id));
        out.put("points", samplePointRepository.findByRunIdOrderBySeqAsc(id));
        out.put("conflicts", conflictRepository.findByRunIdOrderByIdAsc(id));
        out.put("exclusions", exclusionRepository.findByRunIdOrderByFromTsMsAsc(id));
        out.put("boundaryOverrides", boundaryOverrideRepository.findByRunIdOrderByCycleNoAsc(id));
        out.put("amendments", amendmentRepository.findAll().stream()
                .filter(a -> a.getRunId() == id).toList());
        List<DerivedVersion> versions = derivedVersionRepository.findByRunIdOrderByVersionNoAsc(id);
        List<Map<String, Object>> derived = new java.util.ArrayList<>();
        for (DerivedVersion v : versions) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("version", v);
            d.put("cycles", cycleResultRepository.findByDerivedVersionIdOrderByCycleNoAsc(v.getId()));
            derived.add(d);
        }
        out.put("derivedVersions", derived);
        return out;
    }

    @PostMapping("/{id}/tick")
    public Map<String, Object> tick(@PathVariable long id, @RequestBody(required = false) TickRequest req) {
        long tickMs = req == null || req.tickMs() == null ? 300_000L : req.tickMs();
        TickResult result = runService.tick(id, tickMs);
        return Map.of("generated", result.generated,
                "delivered", result.delivered,
                "buffered", result.buffered,
                "blockedByConflict", result.blockedByConflict,
                "completed", result.completed,
                "outcomes", result.outcomes);
    }

    @PostMapping("/{id}/pause")
    public Run pause(@PathVariable long id) {
        return runService.pause(id);
    }

    @PostMapping("/{id}/resume")
    public Run resume(@PathVariable long id) {
        return runService.resume(id);
    }

    @PostMapping("/{id}/cancel")
    public Run cancel(@PathVariable long id) {
        return runService.cancel(id);
    }

    @PostMapping("/{id}/device-fault")
    public Run deviceFault(@PathVariable long id) {
        return runService.deviceFault(id);
    }

    @PostMapping("/{id}/link/offline")
    public Run offline(@PathVariable long id) {
        return runService.setLink(id, false);
    }

    @PostMapping("/{id}/link/online")
    public Run online(@PathVariable long id) {
        return runService.setLink(id, true);
    }

    @PostMapping("/{id}/inject/lost-ack")
    public Map<String, Object> armLostAck(@PathVariable long id) {
        runService.armLostAcknowledgement(id);
        return Map.of("armed", true);
    }

    /** Direct report entry point (used by tests and external device adapters). */
    @PostMapping("/{id}/samples")
    public IngestOutcome report(@PathVariable long id, @RequestBody SampleReportRequest req) {
        return ingestionService.confirm(id, new SampleReport(req.seq(), req.tsMs(),
                req.voltageMv(), req.currentMa(), req.temperatureCd(), req.direction()));
    }

    @PostMapping("/conflicts/{conflictId}/resolve")
    public SampleConflict resolve(@PathVariable long conflictId,
                                  @RequestBody ResolveConflictRequest req) {
        ConflictResolution resolution =
                ConflictResolution.valueOf(orDefault(req.resolution(), "KEEP_EXISTING"));
        return conflictService.resolve(conflictId, resolution);
    }

    @PostMapping("/{id}/exclusions")
    public PointExclusion exclude(@PathVariable long id, @RequestBody ExclusionRequest req) {
        return reviewService.exclude(id, req.fromTsMs(), req.toTsMs(), req.reason());
    }

    @PostMapping("/{id}/boundaries")
    public BoundaryOverride boundary(@PathVariable long id, @RequestBody BoundaryRequest req) {
        return reviewService.overrideBoundary(id, req.cycleNo(), req.boundaryTsMs(), req.note());
    }

    @PostMapping("/{id}/derive")
    public Map<String, Object> derive(@PathVariable long id,
                                      @RequestBody(required = false) DeriveRequest req) {
        Long ruleId = req == null ? null : req.ruleVersionId();
        String reason = req == null || req.reason() == null
                ? "researcher recomputation" : req.reason();
        DerivedVersion version = derivationService.derive(id, ruleId, reason);
        return Map.of("derivedVersionId", version.getId(),
                "versionNo", version.getVersionNo(),
                "status", version.getStatus(),
                "reviewFlags", version.getReviewFlagsJson());
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
