package com.example.batterylab.service;

import com.example.batterylab.domain.*;
import com.example.batterylab.repo.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confirms device reports into raw, append-only sample points.
 *
 * The confirmation window ("reported after success but before the write is acknowledged")
 * is safe because:
 *  - the physical write and the dedup/conflict decision happen in one SQLite transaction
 *    (REQUIRES_NEW so it is committed independently of any caller transaction);
 *  - (run_id, seq) is a database unique key;
 *  - an identical retry of the same seq is answered CONFIRMED without inserting twice;
 *  - a same-seq report with different payload opens/keeps a conflict and never overwrites.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final RunRepository runRepository;
    private final SamplePointRepository samplePointRepository;
    private final SampleConflictRepository conflictRepository;
    private final ProtocolEngine protocolEngine;
    private final ObjectMapper objectMapper;

    public IngestionService(RunRepository runRepository,
                            SamplePointRepository samplePointRepository,
                            SampleConflictRepository conflictRepository,
                            ProtocolEngine protocolEngine,
                            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.samplePointRepository = samplePointRepository;
        this.conflictRepository = conflictRepository;
        this.protocolEngine = protocolEngine;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public IngestOutcome confirm(long runId, SampleReport report) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("unknown run " + runId));

        if (run.isTerminal()) {
            return new IngestOutcome(IngestOutcome.Kind.REFUSED_TERMINAL, false, false,
                    "run is " + run.getStatus());
        }
        if (conflictRepository.existsByRunIdAndStatus(runId, ConflictStatus.OPEN)) {
            return blocked();
        }

        SamplePoint existing = samplePointRepository.findByRunIdAndSeq(runId, report.seq()).orElse(null);
        if (existing != null) {
            if (samePayload(existing, report)) {
                // Idempotent replay after a lost acknowledgement: no extra row, no state move.
                return new IngestOutcome(IngestOutcome.Kind.DUPLICATE_IGNORED, false, false, null);
            }
            registerConflict(runId, existing, report);
            return new IngestOutcome(IngestOutcome.Kind.CONFLICT, false, false,
                    "seq " + report.seq() + " re-reported with a different payload");
        }

        boolean late = report.tsMs() < run.getVirtualNowMs()
                || report.seq() < run.getLastSeqConfirmed();
        SamplePoint point = new SamplePoint(runId, report.seq(), report.tsMs(),
                report.voltageMv(), report.currentMa(), report.temperatureCd(),
                report.direction(), System.currentTimeMillis());
        point.setLate(late);
        samplePointRepository.saveAndFlush(point);

        boolean advanced = false;
        if (!late) {
            run.setLastSeqConfirmed(Math.max(run.getLastSeqConfirmed(), report.seq()));
            ProtocolModel protocol;
            try {
                protocol = ProtocolModel.parse(objectMapper.readTree(run.getProtocolSnapshotJson()));
            } catch (Exception e) {
                throw new IllegalStateException("stored protocol is unreadable", e);
            }
            if (run.getStatus() == RunStatus.CREATED) {
                run.setStatus(RunStatus.RUNNING);
                run.setCycleNo(1);
                protocolEngine.openInitialStep(run, protocol, point);
            }
            if (run.getStatus() == RunStatus.RUNNING) {
                int before = run.getCurrentStepIndex();
                protocolEngine.applyConfirmed(run, protocol, point);
                advanced = run.getCurrentStepIndex() != before
                        || run.getStatus() == RunStatus.COMPLETED;
            }
        }
        runRepository.save(run);
        return new IngestOutcome(IngestOutcome.Kind.CONFIRMED, advanced,
                run.getStatus() == RunStatus.COMPLETED, late ? "late sample recorded" : null);
    }

    private IngestOutcome blocked() {
        return new IngestOutcome(IngestOutcome.Kind.REFUSED_BLOCKED, false, false,
                "an open duplicate-seq conflict must be resolved first");
    }

    private boolean samePayload(SamplePoint p, SampleReport r) {
        return p.getTsMs() == r.tsMs() && p.getVoltageMv() == r.voltageMv()
                && p.getCurrentMa() == r.currentMa() && p.getTemperatureCd() == r.temperatureCd()
                && p.getDirection() == r.direction();
    }

    private void registerConflict(long runId, SamplePoint existing, SampleReport dup) {
        for (SampleConflict open : conflictRepository
                .findByRunIdAndStatusOrderByIdAsc(runId, ConflictStatus.OPEN)) {
            if (open.getSeq() == dup.seq()) {
                return; // the conflicting seq is already parked
            }
        }
        conflictRepository.save(new SampleConflict(
                runId, dup.seq(),
                existing.getVoltageMv(), existing.getCurrentMa(), existing.getTemperatureCd(),
                existing.getTsMs(), existing.getDirection(),
                dup.voltageMv(), dup.currentMa(), dup.temperatureCd(),
                dup.tsMs(), dup.direction(),
                System.currentTimeMillis()));
        log.warn("duplicate seq {} with different payload on run {} -> CONFLICT", dup.seq(), runId);
    }
}
