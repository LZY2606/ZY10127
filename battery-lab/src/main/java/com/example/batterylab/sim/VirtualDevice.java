package com.example.batterylab.sim;

import com.example.batterylab.domain.StepType;
import com.example.batterylab.service.ProtocolModel;
import com.example.batterylab.service.SampleReport;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure deterministic battery-device model.
 *
 * Every phase end lands exactly on the {@link #GRID_MS} sample grid with an exactly-at-limit
 * value, so crossing samples are reproducible boundary points:
 *
 *   CC charge  : 2000 mA, voltage rises linearly from entry to 4200 mV over 1 800 000 ms
 *   CV cutoff  : held at 4200 mV, current falls 2000 -> 100 mA over 300 000 ms
 *   rest       : 0 mA, voltage relaxes linearly to a fixed open-circuit value over 600 000 ms
 *   CC discharge: -2000 mA, voltage falls 4150 -> 3000 mV over 1 920 000 ms
 *
 * No randomness and no wall clock exist in this class: given a state and a target virtual
 * time it produces the exact same reports every time.
 */
public final class VirtualDevice {

    public static final long GRID_MS = 60_000L;
    public static final int CC_MS = 1_800_000;
    public static final int CV_MS = 300_000;
    public static final int DISCHARGE_MS = 1_920_000;
    public static final int CURRENT_MA = 2_000;
    public static final int CUTOFF_MA = 100;
    public static final int LIMIT_TOP_MV = 4_200;
    public static final int LIMIT_BOTTOM_MV = 3_000;
    public static final int OCV_HIGH_MV = 4_150;
    public static final int OCV_LOW_MV = 3_350;

    private VirtualDevice() {
    }

    /**
     * Produces at most one new report (the earliest sample up to targetT) and returns.
     * Zero-time LOOP steps are followed internally so the emitted point always belongs
     * to a real current/voltage phase. Callers deliver the report before calling again,
     * which makes a lost acknowledgement stop the batch at exactly that point.
     */
    public static void advance(SimState s, ProtocolModel protocol, long targetT) {
        int guard = 0;
        while (!s.finished && guard++ <= protocol.steps().size() + 2) {
            if (s.stepIndex >= protocol.steps().size()) {
                s.finished = true;
                return;
            }
            ProtocolModel.Step step = protocol.steps().get(s.stepIndex);
            if (step.type() == StepType.LOOP) {
                int remaining = s.loopCounters.getOrDefault(s.stepIndex, 0);
                if (remaining > 0) {
                    s.loopCounters.put(s.stepIndex, remaining - 1);
                    s.stepIndex = step.jumpIndex();
                    s.cycleNo++;
                    s.phaseEnterT = s.t;
                    s.phaseEnterMv = s.lastProducedMv;
                } else if (s.stepIndex + 1 >= protocol.steps().size()) {
                    s.finished = true;
                    return;
                } else {
                    s.stepIndex++;
                }
                continue;
            }

            long nextGrid = (s.t == 0L && s.nextSeq == 0L) ? 0L : s.t + GRID_MS;
            long phaseEnd = switch (step.type()) {
                case CC_CHARGE -> s.phaseEnterT + CC_MS;
                case CV_CUTOFF -> s.phaseEnterT + CV_MS;
                case REST -> s.phaseEnterT + step.durationMs();
                case CC_DISCHARGE -> s.phaseEnterT + DISCHARGE_MS;
                case LOOP -> Long.MAX_VALUE;
            };
            long emitT = Math.min(nextGrid, phaseEnd);
            if (emitT > targetT) {
                return;
            }

            SampleReport report = model(s, step, emitT);
            s.pending.add(report);
            s.nextSeq = report.seq() + 1;
            s.t = emitT;
            s.lastProducedMv = report.voltageMv();

            if (emitT == phaseEnd) {
                switch (step.type()) {
                    case CC_CHARGE -> {
                        s.stepIndex++;
                        s.phaseEnterT = emitT;
                        s.phaseEnterMv = LIMIT_TOP_MV;
                    }
                    case CV_CUTOFF -> {
                        s.stepIndex++;
                        s.phaseEnterT = emitT;
                        s.phaseEnterMv = LIMIT_TOP_MV;
                        s.restTargetMv = OCV_HIGH_MV;
                    }
                    case REST -> {
                        s.stepIndex++;
                        s.phaseEnterT = emitT;
                        s.phaseEnterMv = s.restTargetMv;
                    }
                    case CC_DISCHARGE -> {
                        s.stepIndex++;
                        s.phaseEnterT = emitT;
                        s.phaseEnterMv = LIMIT_BOTTOM_MV;
                        s.restTargetMv = OCV_LOW_MV;
                    }
                    default -> {
                    }
                }
            }
            return;
        }
    }

    private static SampleReport model(SimState s, ProtocolModel.Step step, long t) {
        long u = t - s.phaseEnterT;
        int mv;
        int ma;
        int direction;
        switch (step.type()) {
            case CC_CHARGE -> {
                ma = CURRENT_MA;
                mv = s.phaseEnterMv
                        + (int) ((long) (LIMIT_TOP_MV - s.phaseEnterMv) * u / CC_MS);
                direction = 1;
            }
            case CV_CUTOFF -> {
                mv = LIMIT_TOP_MV;
                ma = CURRENT_MA - (int) ((long) (CURRENT_MA - CUTOFF_MA) * u / CV_MS);
                direction = 1;
            }
            case REST -> {
                ma = 0;
                long duration = step.durationMs();
                mv = s.phaseEnterMv
                        + (int) ((long) (s.restTargetMv - s.phaseEnterMv) * u / duration);
                direction = 0;
            }
            case CC_DISCHARGE -> {
                ma = -CURRENT_MA;
                mv = s.phaseEnterMv
                        - (int) ((long) (s.phaseEnterMv - LIMIT_BOTTOM_MV) * u / DISCHARGE_MS);
                direction = -1;
            }
            default -> throw new IllegalStateException("no model for " + step.type());
        }
        int temperatureCd = 300 + Math.abs(ma) / 40;
        return new SampleReport(s.nextSeq, t, mv, ma, temperatureCd, direction);
    }
}
