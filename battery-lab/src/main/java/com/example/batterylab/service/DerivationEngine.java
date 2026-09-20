package com.example.batterylab.service;

import com.example.batterylab.domain.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, deterministic derivation from effective raw points under one {@link RuleSpec}.
 *
 * Integration runs INSIDE each published step window over the samples with timestamps in
 * [enterTs, exitTs]. A trapezoid interval therefore never straddles a step transition:
 * the sample sitting exactly on an end boundary belongs to the window it closes (its
 * value participates in that window's last trapezoid), while the interval leaving it
 * already belongs to the next window. REST/LOOP windows carry no throughput.
 *
 * Cycle boundaries are LOOP jump times == next charge enter times, so cycle windows
 * partition the timeline without dropping or double counting any interval.
 */
public final class DerivationEngine {

    private DerivationEngine() {
    }

    public record Window(long enterTs, long exitTs, StepType type, int cycleNo) {
    }

    public record CycleResultData(long derivedVersionId, int cycleNo, long startTsMs,
                                  long endTsMs, Long endPointId, long chargeCapUah,
                                  long dischargeCapUah, int ceBp, InterruptReason reason,
                                  int excludedIntervalCount, int rawPointCount) {
    }

    public static List<CycleResultData> derive(long derivedVersionId,
                                               List<EffectivePoint> points,
                                               List<StepExec> stepExecs,
                                               List<long[]> exclusions,
                                               Map<Integer, Long> boundaryOverrides,
                                               RuleSpec spec,
                                               InterruptReason runInterrupt,
                                               int targetCycles) {
        List<Window> windows = buildWindows(stepExecs, points);
        Map<Integer, long[]> totals = integrateByCycle(points, windows, exclusions, spec);

        List<long[]> boundaries = cycleBoundaries(stepExecs, points);
        List<CycleResultData> cycles = new ArrayList<>();
        long startTs = points.isEmpty() ? 0L : points.get(0).tsMs();
        int count = Math.max(targetCycles, boundaries.size());
        for (int cycleNo = 1; cycleNo <= count; cycleNo++) {
            if (boundaries.size() < cycleNo) {
                break;
            }
            long[] boundary = boundaries.get(cycleNo - 1);
            long endTs = boundary[0];
            Long endPointId = boundary[1];

            Long overrideTs = boundaryOverrides.get(cycleNo);
            if (overrideTs != null) {
                EffectivePoint exact = findPointAt(points, overrideTs);
                if (exact != null && exact.tsMs() >= startTs && exact.tsMs() <= endTs) {
                    endTs = exact.tsMs();
                    endPointId = exact.id();
                }
            }

            long[] t = totals.getOrDefault(cycleNo, new long[]{0L, 0L, 0L});
            int rawCount = 0;
            for (EffectivePoint p : points) {
                if (p.tsMs() >= startTs && p.tsMs() <= endTs) {
                    rawCount++;
                }
            }

            boolean lastCycle = cycleNo == boundaries.size();
            InterruptReason reason = lastCycle && runInterrupt != InterruptReason.NORMAL
                    ? runInterrupt : InterruptReason.NORMAL;

            cycles.add(new CycleResultData(derivedVersionId, cycleNo, startTs, endTs,
                    endPointId, t[0], t[1],
                    Integrator.efficiencyBp(t[0], t[1]), reason, (int) t[2], rawCount));
            startTs = endTs;
        }
        return cycles;
    }

    /** Returns [endTs, pointId] pairs, LOOP jump points plus the final sample if needed. */
    private static List<long[]> cycleBoundaries(List<StepExec> stepExecs,
                                                List<EffectivePoint> points) {
        List<long[]> out = new ArrayList<>();
        for (StepExec exec : stepExecs) {
            if (exec.getStepType() == StepType.LOOP && exec.getExitTsMs() != null) {
                long pid = exec.getExitSeq() == null ? -1L : exec.getExitSeq();
                out.add(new long[]{exec.getExitTsMs(), pid});
            }
        }
        if (!points.isEmpty()) {
            EffectivePoint last = points.get(points.size() - 1);
            if (out.isEmpty() || out.get(out.size() - 1)[0] < last.tsMs()) {
                out.add(new long[]{last.tsMs(), last.id()});
            }
        }
        return out;
    }

    private static List<Window> buildWindows(List<StepExec> stepExecs,
                                             List<EffectivePoint> points) {
        List<Window> windows = new ArrayList<>();
        long fallbackExit = points.isEmpty() ? 0L : points.get(points.size() - 1).tsMs();
        for (StepExec e : stepExecs) {
            long exit = e.getExitTsMs() != null ? e.getExitTsMs() : fallbackExit;
            windows.add(new Window(e.getEnterTsMs(), exit, e.getStepType(), e.getCycleNo()));
        }
        return windows;
    }

    /** @return cycleNo -> [chargeUah, dischargeUah, excludedIntervalCount] */
    private static Map<Integer, long[]> integrateByCycle(List<EffectivePoint> points,
                                                         List<Window> windows,
                                                         List<long[]> exclusions,
                                                         RuleSpec spec) {
        Map<Integer, long[]> result = new HashMap<>();
        for (Window w : windows) {
            if (w.type() == StepType.REST || w.type() == StepType.LOOP) {
                continue;
            }
            List<EffectivePoint> inWindow = new ArrayList<>();
            for (EffectivePoint p : points) {
                if (p.tsMs() >= w.enterTs() && p.tsMs() <= w.exitTs()) {
                    inWindow.add(p);
                }
            }
            long charge = 0L;
            long discharge = 0L;
            int excluded = 0;
            for (int k = 1; k < inWindow.size(); k++) {
                EffectivePoint a = inWindow.get(k - 1);
                EffectivePoint b = inWindow.get(k);
                boolean bad = isExcluded(a, b, exclusions)
                        || outOfTemperature(a, spec) || outOfTemperature(b, spec);
                if (bad) {
                    excluded++;
                    continue;
                }
                int i1 = spec.effectiveChargeCurrent(a.currentMa(), a.direction());
                int i2 = spec.effectiveChargeCurrent(b.currentMa(), b.direction());
                long signed = Integrator.intervalUah(i1, i2, b.tsMs() - a.tsMs());
                charge += Integrator.chargePart(signed);
                discharge += Integrator.dischargePart(signed);
            }
            long[] totals = result.computeIfAbsent(w.cycleNo(),
                    key -> new long[]{0L, 0L, 0L});
            totals[0] += charge;
            totals[1] += discharge;
            totals[2] += excluded;
        }
        return result;
    }

    private static EffectivePoint findPointAt(List<EffectivePoint> points, long ts) {
        for (EffectivePoint p : points) {
            if (p.tsMs() == ts) {
                return p;
            }
        }
        return null;
    }

    private static boolean isExcluded(EffectivePoint a, EffectivePoint b, List<long[]> ranges) {
        for (long[] range : ranges) {
            if (a.tsMs() < range[1] && b.tsMs() > range[0]) {
                return true;
            }
        }
        return false;
    }

    private static boolean outOfTemperature(EffectivePoint p, RuleSpec spec) {
        return p.temperatureCd() < spec.minTemperatureCd()
                || p.temperatureCd() > spec.maxTemperatureCd();
    }
}
