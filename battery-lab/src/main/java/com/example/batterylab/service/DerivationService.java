package com.example.batterylab.service;

import com.example.batterylab.domain.*;
import com.example.batterylab.repo.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates immutable derived versions from raw points + researcher inputs + a rule version.
 * Old versions are never rewritten: a new derivation supersedes the previous current one
 * and carries review flags (late samples, exclusions, amendments, boundary overrides).
 */
@Service
public class DerivationService {

    private final RunRepository runRepository;
    private final RuleVersionRepository ruleRepository;
    private final SamplePointRepository samplePointRepository;
    private final PointAmendmentRepository amendmentRepository;
    private final PointExclusionRepository exclusionRepository;
    private final BoundaryOverrideRepository boundaryOverrideRepository;
    private final StepExecRepository stepExecRepository;
    private final DerivedVersionRepository derivedVersionRepository;
    private final CycleResultRepository cycleResultRepository;
    private final ObjectMapper objectMapper;

    public DerivationService(RunRepository runRepository, RuleVersionRepository ruleRepository,
                             SamplePointRepository samplePointRepository,
                             PointAmendmentRepository amendmentRepository,
                             PointExclusionRepository exclusionRepository,
                             BoundaryOverrideRepository boundaryOverrideRepository,
                             StepExecRepository stepExecRepository,
                             DerivedVersionRepository derivedVersionRepository,
                             CycleResultRepository cycleResultRepository,
                             ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.ruleRepository = ruleRepository;
        this.samplePointRepository = samplePointRepository;
        this.amendmentRepository = amendmentRepository;
        this.exclusionRepository = exclusionRepository;
        this.boundaryOverrideRepository = boundaryOverrideRepository;
        this.stepExecRepository = stepExecRepository;
        this.derivedVersionRepository = derivedVersionRepository;
        this.cycleResultRepository = cycleResultRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DerivedVersion derive(long runId, Long ruleVersionId, String reason) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("unknown run " + runId));
        RuleVersion ruleVersion = ruleRepository
                .findById(ruleVersionId != null ? ruleVersionId : run.getRuleVersionId())
                .orElseThrow(() -> new IllegalArgumentException("unknown rule version"));
        RuleSpec spec;
        try {
            spec = RuleSpec.parse(objectMapper.readTree(ruleVersion.getSpecJson()));
        } catch (Exception e) {
            throw new IllegalStateException("rule spec unreadable", e);
        }

        List<SamplePoint> raw = samplePointRepository.findByRunIdOrderByTsMsAscSeqAsc(runId);
        List<EffectivePoint> points = new ArrayList<>(raw.size());
        boolean hasAmendment = false;
        for (SamplePoint p : raw) {
            PointAmendment amendment = amendmentRepository.findByRunIdAndSeq(runId, p.getSeq())
                    .orElse(null);
            if (amendment != null) {
                hasAmendment = true;
            }
            points.add(EffectivePoint.of(p, amendment));
        }

        List<PointExclusion> exclusions = exclusionRepository.findByRunIdOrderByFromTsMsAsc(runId);
        List<long[]> ranges = new ArrayList<>();
        for (PointExclusion e : exclusions) {
            ranges.add(new long[]{e.getFromTsMs(), e.getToTsMs()});
        }
        Map<Integer, Long> overrides = new HashMap<>();
        for (BoundaryOverride b : boundaryOverrideRepository.findByRunIdOrderByCycleNoAsc(runId)) {
            overrides.put(b.getCycleNo(), b.getBoundaryTsMs());
        }
        List<StepExec> stepExecs = stepExecRepository.findByRunIdOrderByIdAsc(runId);

        int targetCycles = 0;
        for (StepExec e : stepExecs) {
            if (e.getStepType() == StepType.CC_CHARGE) {
                targetCycles = Math.max(targetCycles, e.getCycleNo());
            }
        }
        targetCycles = Math.max(targetCycles, run.getCycleNo());

        List<DerivedVersion> existing =
                derivedVersionRepository.findByRunIdOrderByVersionNoAsc(runId);
        int versionNo = existing.stream().mapToInt(DerivedVersion::getVersionNo).max().orElse(0) + 1;
        for (DerivedVersion v : existing) {
            if (v.getStatus() == DerivedVersionStatus.PUBLISHED
                    || v.getStatus() == DerivedVersionStatus.NEEDS_REVIEW) {
                v.setStatus(DerivedVersionStatus.SUPERSEDED);
                derivedVersionRepository.save(v);
            }
        }

        List<DerivationEngine.CycleResultData> data = DerivationEngine.derive(
                -1L, points, stepExecs, ranges, overrides, spec,
                run.getInterruptReason(), targetCycles);

        List<String> flags = new ArrayList<>();
        boolean late = points.stream().anyMatch(EffectivePoint::late);
        if (late) {
            flags.add("LATE_SAMPLE");
        }
        if (!exclusions.isEmpty()) {
            flags.add("EXCLUDED_INTERVAL");
        }
        if (hasAmendment) {
            flags.add("AMENDED_POINT");
        }
        if (!overrides.isEmpty()) {
            flags.add("BOUNDARY_OVERRIDE");
        }
        if (run.getStatus() == RunStatus.DEVICE_FAULT
                || run.getInterruptReason() == InterruptReason.DEVICE_FAULT) {
            flags.add("DEVICE_FAULT");
        }
        DerivedVersionStatus status = flags.isEmpty()
                ? DerivedVersionStatus.PUBLISHED : DerivedVersionStatus.NEEDS_REVIEW;

        String inputs = buildInputsJson(exclusions, overrides, late, hasAmendment);
        DerivedVersion version = new DerivedVersion(runId, versionNo, ruleVersion.getId(),
                status, inputs, ruleVersion.getSpecJson(), writeFlags(flags),
                System.currentTimeMillis(), reason);
        version = derivedVersionRepository.save(version);

        for (DerivationEngine.CycleResultData d : data) {
            cycleResultRepository.save(new CycleResult(version.getId(), d.cycleNo(),
                    d.startTsMs(), d.endTsMs(), d.endPointId(), d.chargeCapUah(),
                    d.dischargeCapUah(), d.ceBp(), d.reason(), d.excludedIntervalCount(),
                    d.rawPointCount()));
        }
        return version;
    }

    private String buildInputsJson(List<PointExclusion> exclusions,
                                   Map<Integer, Long> overrides, boolean late,
                                   boolean hasAmendment) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            ArrayNode ex = root.putArray("exclusionIds");
            exclusions.forEach(e -> ex.add(e.getId()));
            ObjectNode ov = root.putObject("boundaryOverrides");
            overrides.forEach((cycle, ts) -> ov.put(String.valueOf(cycle), ts));
            root.put("containsLateSample", late);
            root.put("containsAmendment", hasAmendment);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize derivation inputs", e);
        }
    }

    private String writeFlags(List<String> flags) {
        try {
            return objectMapper.writeValueAsString(flags);
        } catch (Exception e) {
            return "[]";
        }
    }
}
