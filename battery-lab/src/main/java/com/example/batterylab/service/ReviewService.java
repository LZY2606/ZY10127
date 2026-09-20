package com.example.batterylab.service;

import com.example.batterylab.domain.BoundaryOverride;
import com.example.batterylab.domain.PointExclusion;
import com.example.batterylab.domain.Run;
import com.example.batterylab.repo.BoundaryOverrideRepository;
import com.example.batterylab.repo.PointExclusionRepository;
import com.example.batterylab.repo.RunRepository;
import com.example.batterylab.repo.SamplePointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Researcher review operations: exclude a sensor-fault interval and correct a boundary. */
@Service
public class ReviewService {

    private final RunRepository runRepository;
    private final PointExclusionRepository exclusionRepository;
    private final BoundaryOverrideRepository boundaryOverrideRepository;
    private final SamplePointRepository samplePointRepository;

    public ReviewService(RunRepository runRepository,
                         PointExclusionRepository exclusionRepository,
                         BoundaryOverrideRepository boundaryOverrideRepository,
                         SamplePointRepository samplePointRepository) {
        this.runRepository = runRepository;
        this.exclusionRepository = exclusionRepository;
        this.boundaryOverrideRepository = boundaryOverrideRepository;
        this.samplePointRepository = samplePointRepository;
    }

    @Transactional
    public PointExclusion exclude(long runId, long fromTsMs, long toTsMs, String reason) {
        runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("unknown run " + runId));
        if (toTsMs <= fromTsMs) {
            throw new IllegalArgumentException("exclusion toTsMs must be greater than fromTsMs");
        }
        return exclusionRepository.save(new PointExclusion(runId, fromTsMs, toTsMs,
                reason == null || reason.isBlank() ? "sensor fault" : reason,
                System.currentTimeMillis()));
    }

    @Transactional
    public BoundaryOverride overrideBoundary(long runId, int cycleNo, long boundaryTsMs,
                                             String note) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("unknown run " + runId));
        boolean exists = samplePointRepository
                .findByRunIdOrderByTsMsAscSeqAsc(runId).stream()
                .anyMatch(p -> p.getTsMs() == boundaryTsMs);
        if (!exists) {
            throw new IllegalArgumentException(
                    "boundary must align to an existing raw point timestamp");
        }
        String noteValue = note == null || note.isBlank() ? "corrected" : note;
        return boundaryOverrideRepository.findByRunIdAndCycleNo(runId, cycleNo)
                .map(existing -> replace(existing, boundaryTsMs, noteValue))
                .orElseGet(() -> boundaryOverrideRepository.save(
                        new BoundaryOverride(runId, cycleNo, boundaryTsMs, noteValue,
                                System.currentTimeMillis())));
    }

    private BoundaryOverride replace(BoundaryOverride existing, long boundaryTsMs, String note) {
        boundaryOverrideRepository.delete(existing);
        boundaryOverrideRepository.flush();
        return boundaryOverrideRepository.save(new BoundaryOverride(existing.getRunId(),
                existing.getCycleNo(), boundaryTsMs, note, System.currentTimeMillis()));
    }
}
