package com.example.batterylab;

import com.example.batterylab.domain.*;
import com.example.batterylab.repo.*;
import com.example.batterylab.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BatteryLabIntegrationTest {

    @Autowired RunService runService;
    @Autowired IngestionService ingestionService;
    @Autowired ConflictService conflictService;
    @Autowired ReviewService reviewService;
    @Autowired DerivationService derivationService;
    @Autowired RunRepository runRepository;
    @Autowired SamplePointRepository samplePointRepository;
    @Autowired SampleConflictRepository conflictRepository;
    @Autowired DerivedVersionRepository derivedVersionRepository;
    @Autowired CycleResultRepository cycleResultRepository;
    @Autowired StepExecRepository stepExecRepository;
    @Autowired RuleVersionRepository ruleRepository;
    @Autowired ObjectMapper objectMapper;

    private long createRun() {
        return runService.createRun("lab-standard", "1.0.0", "DEV-TEST",
                "generic-channel", "1.0.0", "capacity-rules", "1.0.0").getId();
    }

    private SampleReport report(long seq, long ts, int mv, int ma, int cd, int dir) {
        return new SampleReport(seq, ts, mv, ma, cd, dir);
    }

    private void driveToCompletion(long runId) {
        for (int i = 0; i < 10; i++) {
            var r = runService.tick(runId, 3_600_000L);
            if (r.completed) {
                return;
            }
        }
        fail("run did not complete");
    }

    // ------------------------------------------------------------------
    // 1. Crash inside the confirmation window: the device retries with the
    //    identical seq+payload; the point must be confirmed exactly once.
    // ------------------------------------------------------------------
    @Test
    void retryAfterLostAcknowledgementConfirmsExactlyOnePoint() {
        long runId = createRun();
        runService.armLostAcknowledgement(runId);
        var first = runService.tick(runId, 300_000L);
        // The first produced point was confirmed then its ack was "lost": it stays pending.
        assertEquals(1L, first.generated);
        assertTrue(first.buffered >= 1);

        // Technician verifies the link and puts the device back online; it retries.
        runService.setLink(runId, true);
        var replay = runService.tick(runId, 0L);
        assertEquals(0L, replay.generated, "a zero-time tick produces no new sample");
        assertTrue(replay.outcomes.stream()
                .anyMatch(o -> o.kind() == IngestOutcome.Kind.DUPLICATE_IGNORED));
        assertEquals(1L, samplePointRepository.countByRunId(runId),
                "seq 0 is stored once, not twice after the retry");

        // Sampling then continues and the remaining five points confirm normally.
        var continued = runService.tick(runId, 300_000L);
        assertEquals(5L, continued.generated);
        assertEquals(6L, samplePointRepository.countByRunId(runId));
        var seqs = samplePointRepository.findByRunIdOrderBySeqAsc(runId)
                .stream().map(SamplePoint::getSeq).toList();
        assertEquals(List.of(0L, 1L, 2L, 3L, 4L, 5L), seqs);
    }

    // ------------------------------------------------------------------
    // 2. Same device sequence re-reported with a DIFFERENT payload enters a
    //    conflict state and never overwrites the confirmed point.
    // ------------------------------------------------------------------
    @Test
    void duplicateSequenceWithDifferentPayloadOpensConflictWithoutOverwrite() {
        long runId = createRun();
        var outcome = ingestionService.confirm(runId, report(0, 0L, 3300, 2000, 350, 1));
        assertEquals(IngestOutcome.Kind.CONFIRMED, outcome.kind());

        var conflictOutcome = ingestionService.confirm(runId, report(0, 0L, 3311, 2000, 351, 1));
        assertEquals(IngestOutcome.Kind.CONFLICT, conflictOutcome.kind());

        SamplePoint stored = samplePointRepository.findByRunIdAndSeq(runId, 0).orElseThrow();
        assertEquals(3300, stored.getVoltageMv(), "confirmed payload must not be overwritten");
        assertEquals(1, conflictRepository
                .findByRunIdAndStatusOrderByIdAsc(runId, ConflictStatus.OPEN).size());

        // While the conflict is open, further NEW sequences are refused.
        var blocked = ingestionService.confirm(runId, report(1, 60_000L, 3330, 2000, 350, 1));
        assertEquals(IngestOutcome.Kind.REFUSED_BLOCKED, blocked.kind());

        // Researcher keeps the original; new sequences flow again.
        SampleConflict open = conflictRepository
                .findByRunIdAndStatusOrderByIdAsc(runId, ConflictStatus.OPEN).get(0);
        conflictService.resolve(open.getId(), ConflictResolution.KEEP_EXISTING);
        var after = ingestionService.confirm(runId, report(1, 60_000L, 3330, 2000, 350, 1));
        assertEquals(IngestOutcome.Kind.CONFIRMED, after.kind());
    }

    @Test
    void conflictAcceptNewKeepsRawPointButDerivesFromAmendment() {
        long runId = createRun();
        ingestionService.confirm(runId, report(0, 0L, 3300, 2000, 350, 1));
        ingestionService.confirm(runId, report(0, 0L, 3400, 2000, 350, 1));
        SampleConflict open = conflictRepository
                .findByRunIdAndStatusOrderByIdAsc(runId, ConflictStatus.OPEN).get(0);
        conflictService.resolve(open.getId(), ConflictResolution.ACCEPT_NEW);

        SamplePoint raw = samplePointRepository.findByRunIdAndSeq(runId, 0).orElseThrow();
        assertEquals(3300, raw.getVoltageMv(), "raw point stays for audit");
        var version = derivationService.derive(runId, null, "amended review");
        assertEquals(DerivedVersionStatus.NEEDS_REVIEW, version.getStatus());
        assertTrue(version.getReviewFlagsJson().contains("AMENDED_POINT"));
    }

    // ------------------------------------------------------------------
    // 3. A late (out-of-order) sample is retained but never moves a
    //    published step; it flags a reviewed derived version instead.
    // ------------------------------------------------------------------
    @Test
    void lateSampleDoesNotRewindStepsButFlagsReview() {
        long runId = createRun();
        runService.tick(runId, 1_900_000L); // crosses CC charge at t=1800000
        Run run = runRepository.findById(runId).orElseThrow();
        assertTrue(run.getCurrentStepIndex() >= 1);
        long clockAfterCrossing = run.getVirtualNowMs();

        var late = ingestionService.confirm(runId,
                report(999, clockAfterCrossing - 120_000L, 3400, 2000, 350, 1));
        assertEquals(IngestOutcome.Kind.CONFIRMED, late.kind());

        SamplePoint latePoint = samplePointRepository.findByRunIdAndSeq(runId, 999).orElseThrow();
        assertTrue(latePoint.isLate());
        Run after = runRepository.findById(runId).orElseThrow();
        assertEquals(clockAfterCrossing, after.getVirtualNowMs(), "clock must not go backwards");

        var version = derivationService.derive(runId, null, "late sample review");
        assertEquals(DerivedVersionStatus.NEEDS_REVIEW, version.getStatus());
        assertTrue(version.getReviewFlagsJson().contains("LATE_SAMPLE"));
    }

    // ------------------------------------------------------------------
    // 4. Crossing sample lands exactly on the step end point; boundary
    //    attribution gives consistent per-cycle capacities/CE and closes
    //    three cycles.
    // ------------------------------------------------------------------
    @Test
    void exactBoundarySamplesDriveThreeConsistentCycles() {
        long runId = createRun();
        driveToCompletion(runId);
        Run run = runRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.COMPLETED, run.getStatus());

        // Every crossing value is exactly at the limit and every exit is on the 60s grid.
        for (StepExec s : stepExecRepository.findByRunIdOrderByIdAsc(runId)) {
            if (s.getExitTsMs() != null && s.getStepType() != StepType.LOOP) {
                assertEquals(0L, s.getExitTsMs() % 60_000L);
            }
        }
        var version = derivationService.derive(runId, null, "initial");
        List<CycleResult> cycles =
                cycleResultRepository.findByDerivedVersionIdOrderByCycleNoAsc(version.getId());
        assertEquals(3, cycles.size());
        for (CycleResult c : cycles) {
            assertEquals(InterruptReason.NORMAL, c.getInterruptReason());
            assertTrue(c.getChargeCapUah() > 1_000_000L);
            assertTrue(c.getDischargeCapUah() > 1_000_000L);
            // Repeated cycles in this deterministic model are identical after cycle 1's
            // first-charge start point; CE sits in a plausible physical band.
            assertTrue(c.getCoulombicEfficiencyBp() > 9000 && c.getCoulombicEfficiencyBp() < 10_000,
                    "CE out of band: " + c.getCoulombicEfficiencyBp());
        }
        // Cycles 2 and 3 must be bit-for-bit identical under the deterministic model.
        assertEquals(cycles.get(1).getChargeCapUah(), cycles.get(2).getChargeCapUah());
        assertEquals(cycles.get(1).getDischargeCapUah(), cycles.get(2).getDischargeCapUah());
    }

    // ------------------------------------------------------------------
    // 5. Re-derivation under another rule version appends a new immutable
    //    version, supersedes the old one, and never rewrites old results.
    // ------------------------------------------------------------------
    @Test
    void rederiveUnderDifferentRuleVersionAppendsAndPreserves() {
        long runId = createRun();
        driveToCompletion(runId);
        DerivedVersion v1 = derivationService.derive(runId, null, "rules 1.0.0");
        List<CycleResult> v1Cycles =
                cycleResultRepository.findByDerivedVersionIdOrderByCycleNoAsc(v1.getId());
        long v1FirstCharge = v1Cycles.get(0).getChargeCapUah();

        long rule2Id = ruleRepository.findByNameAndVersion("capacity-rules", "2.0.0")
                .orElseThrow().getId();
        DerivedVersion v2 = derivationService.derive(runId, rule2Id, "rules 2.0.0");
        assertNotEquals(v1.getId(), v2.getId());
        assertEquals(DerivedVersionStatus.SUPERSEDED,
                derivedVersionRepository.findById(v1.getId()).orElseThrow().getStatus());
        assertEquals(DerivedVersionStatus.PUBLISHED, v2.getStatus());

        // v1 rows are still present and unchanged.
        List<CycleResult> v1Reread =
                cycleResultRepository.findByDerivedVersionIdOrderByCycleNoAsc(v1.getId());
        assertEquals(v1FirstCharge, v1Reread.get(0).getChargeCapUah());
    }

    // ------------------------------------------------------------------
    // 6. Excluding a sensor fault interval removes those trapezoids only
    //    from the new derivation; raw points remain.
    // ------------------------------------------------------------------
    @Test
    void exclusionOnlyAffectsNewDerivedVersion() {
        long runId = createRun();
        runService.tick(runId, 200_000L);
        reviewService.exclude(runId, 60_000L, 180_000L, "temp sensor stuck");
        var version = derivationService.derive(runId, null, "fault window excluded");
        CycleResult c1 = cycleResultRepository
                .findByDerivedVersionIdOrderByCycleNoAsc(version.getId()).get(0);
        assertTrue(c1.getExcludedIntervalCount() >= 2);
        assertTrue(version.getReviewFlagsJson().contains("EXCLUDED_INTERVAL"));
        assertEquals(4L, samplePointRepository.countByRunId(runId),
                "exclusion never deletes raw points");
    }

    // ------------------------------------------------------------------
    // 7. Terminal states are distinct and cannot be revived.
    // ------------------------------------------------------------------
    @Test
    void terminalStatesAreDistinctAndNeverRevived() {
        long cancelled = createRun();
        runService.cancel(cancelled);
        assertThrows(IllegalStateException.class, () -> runService.resume(cancelled));
        assertThrows(IllegalStateException.class, () -> runService.tick(cancelled, 60_000L));
        var refused = ingestionService.confirm(cancelled, report(0, 0L, 3300, 2000, 350, 1));
        assertEquals(IngestOutcome.Kind.REFUSED_TERMINAL, refused.kind());

        long faulted = createRun();
        runService.tick(faulted, 60_000L);
        runService.deviceFault(faulted);
        assertEquals(RunStatus.DEVICE_FAULT, runRepository.findById(faulted).orElseThrow().getStatus());
        assertThrows(IllegalStateException.class, () -> runService.resume(faulted));

        long paused = createRun();
        runService.tick(paused, 60_000L);
        runService.pause(paused);
        runService.resume(paused);
        assertEquals(RunStatus.RUNNING, runRepository.findById(paused).orElseThrow().getStatus());
    }

    // ------------------------------------------------------------------
    // 8b. After a process restart a RUNNING row is parked to PAUSED and can
    //     then be resumed; terminal rows remain untouched.
    // ------------------------------------------------------------------
    @Test
    void restartParksRunningRunButLeavesTerminalAlone() {
        long runId = createRun();
        runService.tick(runId, 120_000L);
        assertEquals(RunStatus.RUNNING, runRepository.findById(runId).orElseThrow().getStatus());

        new com.example.batterylab.config.StartupRecovery(runRepository).run(null);
        Run parked = runRepository.findById(runId).orElseThrow();
        assertEquals(RunStatus.PAUSED, parked.getStatus());
        assertEquals(InterruptReason.PAUSED, parked.getInterruptReason());

        // Resuming continues from the exact virtual clock and confirmed sequences.
        long clock = parked.getVirtualNowMs();
        long seq = parked.getLastSeqConfirmed();
        runService.resume(runId);
        var more = runService.tick(runId, 60_000L);
        Run advanced = runRepository.findById(runId).orElseThrow();
        assertTrue(advanced.getVirtualNowMs() > clock);
        assertTrue(advanced.getLastSeqConfirmed() > seq);

        long completed = createRun();
        driveToCompletion(completed);
        new com.example.batterylab.config.StartupRecovery(runRepository).run(null);
        assertEquals(RunStatus.COMPLETED, runRepository.findById(completed).orElseThrow().getStatus());
    }

    // ------------------------------------------------------------------
    // 8c. A boundary override must align to an existing raw point and only
    //     affects a freshly derived version.
    // ------------------------------------------------------------------
    @Test
    void boundaryOverrideSnapsToRawPointAndAppendsVersion() {
        long runId = createRun();
        driveToCompletion(runId);
        DerivedVersion v1 = derivationService.derive(runId, null, "auto boundaries");
        var points = samplePointRepository.findByRunIdOrderByTsMsAscSeqAsc(runId);
        // Move cycle 1 end one discharge sample earlier.
        long autoEnd = cycleResultRepository
                .findByDerivedVersionIdOrderByCycleNoAsc(v1.getId()).get(0).getEndTsMs();
        long moved = points.stream().filter(p -> p.getTsMs() == autoEnd - 60_000L)
                .findFirst().orElseThrow().getTsMs();
        reviewService.overrideBoundary(runId, 1, moved, "reviewer corrected endpoint");
        DerivedVersion v2 = derivationService.derive(runId, null, "boundary corrected");
        var cycles2 = cycleResultRepository.findByDerivedVersionIdOrderByCycleNoAsc(v2.getId());
        assertEquals(moved, cycles2.get(0).getEndTsMs());
        assertTrue(v2.getReviewFlagsJson().contains("BOUNDARY_OVERRIDE"));

        // An override that does not land on a raw point is rejected.
        assertThrows(IllegalArgumentException.class,
                () -> reviewService.overrideBoundary(runId, 2, moved + 12_345L, "bad"));
    }

    // ------------------------------------------------------------------
    // 9. Offline window: produced samples are buffered and replayed once;
    //    reconnect neither loses nor duplicates them.
    // ------------------------------------------------------------------
    @Test
    void offlineBufferReplaysWithoutLossOrDuplication() {
        long runId = createRun();
        runService.setLink(runId, false);
        var offlineTick = runService.tick(runId, 300_000L);
        assertEquals(6L, offlineTick.generated);
        assertEquals(6L, offlineTick.buffered);
        assertEquals(0L, samplePointRepository.countByRunId(runId));

        runService.setLink(runId, true);
        var replay = runService.tick(runId, 0L);
        assertEquals(6L, replay.delivered);
        assertEquals(6L, samplePointRepository.countByRunId(runId));

        // A second tick replays nothing new (all seqs already confirmed).
        var replay2 = runService.tick(runId, 0L);
        assertEquals(6L, samplePointRepository.countByRunId(runId));
        assertEquals(0L, replay2.generated);
    }
}
