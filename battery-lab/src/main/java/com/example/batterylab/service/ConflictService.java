package com.example.batterylab.service;

import com.example.batterylab.domain.*;
import com.example.batterylab.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves an OPEN duplicate-seq conflict.
 *
 * KEEP_EXISTING: the confirmed raw point wins; the conflicting report is discarded.
 * ACCEPT_NEW: the raw point still stays as confirmed (append-only audit), but the
 * conflicting values are stored as a {@link PointAmendment} used by future derivations.
 * In both cases an old derived version is left untouched; the researcher must (re)derive.
 */
@Service
public class ConflictService {

    private final SampleConflictRepository conflictRepository;
    private final SamplePointRepository samplePointRepository;
    private final PointAmendmentRepository amendmentRepository;

    public ConflictService(SampleConflictRepository conflictRepository,
                           SamplePointRepository samplePointRepository,
                           PointAmendmentRepository amendmentRepository) {
        this.conflictRepository = conflictRepository;
        this.samplePointRepository = samplePointRepository;
        this.amendmentRepository = amendmentRepository;
    }

    @Transactional
    public SampleConflict resolve(long conflictId, ConflictResolution resolution) {
        SampleConflict conflict = conflictRepository.findById(conflictId)
                .orElseThrow(() -> new IllegalArgumentException("unknown conflict " + conflictId));
        if (conflict.getStatus() != ConflictStatus.OPEN) {
            throw new IllegalStateException("conflict already " + conflict.getStatus());
        }
        if (resolution == ConflictResolution.ACCEPT_NEW) {
            SamplePoint point = samplePointRepository
                    .findByRunIdAndSeq(conflict.getRunId(), conflict.getSeq())
                    .orElseThrow(() -> new IllegalStateException("confirmed point missing"));
            amendmentRepository.findByRunIdAndSeq(conflict.getRunId(), conflict.getSeq())
                    .orElseGet(() -> amendmentRepository.save(new PointAmendment(
                            point.getId(), conflict.getRunId(), conflict.getSeq(),
                            conflict.getDuplicateMv(), conflict.getDuplicateMa(),
                            conflict.getDuplicateCd(), conflict.getDuplicateDirection(),
                            System.currentTimeMillis())));
            conflict.setStatus(ConflictStatus.ACCEPTED_AMENDMENT);
        } else {
            conflict.setStatus(ConflictStatus.KEPT_EXISTING);
        }
        return conflictRepository.save(conflict);
    }
}
