package com.example.batterylab.service;

import com.example.batterylab.domain.*;
import com.example.batterylab.repo.StepExecRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies confirmed samples to a run's protocol state machine.
 *
 * Invariants:
 *  - threshold steps leave only on a NEW confirmed sample proving the crossing
 *    (a sample exactly at the limit counts);
 *  - REST leaves only once a confirmed sample is at/past its duration;
 *  - late (out-of-order) samples never move step state and never rewrite step windows;
 *  - terminal runs refuse every further sample;
 *  - the crossing sample closes the old step and opens the next one at the same
 *    timestamp, so a boundary point belongs to the window it closes.
 */
@Service
public class ProtocolEngine {

    private static final TypeReference<HashMap<String, Integer>> MAP_TYPE = new TypeReference<>() {
    };

    private final StepExecRepository stepExecRepository;
    private final ObjectMapper objectMapper;

    public ProtocolEngine(StepExecRepository stepExecRepository, ObjectMapper objectMapper) {
        this.stepExecRepository = stepExecRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public StepExec findOpenStep(long runId) {
        StepExec open = null;
        for (StepExec s : stepExecRepository.findByRunIdOrderByIdAsc(runId)) {
            if (s.getExitTsMs() == null) {
                open = s;
            }
        }
        return open;
    }

    @Transactional
    public StepExec openInitialStep(Run run, ProtocolModel protocol, SamplePoint first) {
        int idx = run.getCurrentStepIndex();
        return stepExecRepository.save(new StepExec(run.getId(), idx, run.getCycleNo(),
                first.getTsMs(), first.getSeq(), protocol.steps().get(idx).type()));
    }

    private Map<Integer, Integer> loadCounters(Run run, ProtocolModel protocol) {
        try {
            Map<String, Integer> raw = objectMapper.readValue(
                    run.getLoopCountersJson() == null ? "{}" : run.getLoopCountersJson(),
                    MAP_TYPE);
            Map<Integer, Integer> counters = new HashMap<>();
            raw.forEach((k, v) -> counters.put(Integer.parseInt(k), v));
            boolean changed = false;
            for (int i = 0; i < protocol.steps().size(); i++) {
                if (protocol.steps().get(i).type() == StepType.LOOP && !counters.containsKey(i)) {
                    // repetitions = total cycles; the first pass already entered the body.
                    counters.put(i, Math.max(0, protocol.steps().get(i).repetitions() - 1));
                    changed = true;
                }
            }
            if (changed) {
                storeCounters(run, counters);
            }
            return counters;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read loop counters for run " + run.getId(), e);
        }
    }

    private void storeCounters(Run run, Map<Integer, Integer> counters) {
        try {
            Map<String, Integer> raw = new HashMap<>();
            counters.forEach((k, v) -> raw.put(String.valueOf(k), v));
            run.setLoopCountersJson(objectMapper.writeValueAsString(raw));
        } catch (Exception e) {
            throw new IllegalStateException("cannot store loop counters", e);
        }
    }

    @Transactional
    public void applyConfirmed(Run run, ProtocolModel protocol, SamplePoint point) {
        if (point.isLate() || point.getTsMs() < run.getVirtualNowMs()) {
            return;
        }
        Map<Integer, Integer> counters = loadCounters(run, protocol);

        int guard = 0;
        while (run.getStatus() == RunStatus.RUNNING
                && guard++ <= protocol.steps().size() * 2 + 4) {
            StepExec openStep = findOpenStep(run.getId());
            if (openStep == null) {
                break;
            }
            int idx = run.getCurrentStepIndex();
            ProtocolModel.Step step = protocol.steps().get(idx);
            boolean leaves = switch (step.type()) {
                case CC_CHARGE -> point.getVoltageMv() >= step.limitMv();
                case CC_DISCHARGE -> point.getVoltageMv() <= step.limitMv();
                case CV_CUTOFF -> Math.abs(point.getCurrentMa()) <= step.currentMa();
                case REST -> point.getTsMs() - openStep.getEnterTsMs() >= step.durationMs();
                case LOOP -> true;
            };
            if (!leaves) {
                break;
            }
            closeStep(openStep, point, exitReason(step.type()));

            switch (step.type()) {
                case LOOP -> {
                    int remaining = counters.getOrDefault(idx, 0);
                    if (remaining > 0) {
                        counters.put(idx, remaining - 1);
                        enter(run, protocol, point, step.jumpIndex());
                    } else if (idx + 1 < protocol.steps().size()) {
                        enter(run, protocol, point, idx + 1);
                    } else {
                        complete(run);
                    }
                }
                case CC_DISCHARGE -> {
                    if (idx + 1 >= protocol.steps().size()) {
                        complete(run);
                    } else {
                        enter(run, protocol, point, idx + 1);
                    }
                }
                default -> enter(run, protocol, point, idx + 1);
            }
        }
        storeCounters(run, counters);
        run.setVirtualNowMs(Math.max(run.getVirtualNowMs(), point.getTsMs()));
    }

    private void complete(Run run) {
        run.setStatus(RunStatus.COMPLETED);
        run.setInterruptReason(InterruptReason.NORMAL);
    }

    private String exitReason(StepType type) {
        return switch (type) {
            case CC_CHARGE, CC_DISCHARGE -> "VOLTAGE_LIMIT_REACHED";
            case CV_CUTOFF -> "CURRENT_CUTOFF_REACHED";
            case REST -> "DURATION_ELAPSED";
            case LOOP -> "LOOP_JUMP";
        };
    }

    private void closeStep(StepExec openStep, SamplePoint point, String reason) {
        openStep.setExitTsMs(point.getTsMs());
        openStep.setExitSeq(point.getSeq());
        openStep.setExitReason(reason);
        stepExecRepository.save(openStep);
    }

    private void enter(Run run, ProtocolModel protocol, SamplePoint point, int nextIndex) {
        run.setCurrentStepIndex(nextIndex);
        ProtocolModel.Step next = protocol.steps().get(nextIndex);
        if (next.type() == StepType.CC_CHARGE) {
            run.setCycleNo(run.getCycleNo() + 1);
        }
        stepExecRepository.save(new StepExec(run.getId(), nextIndex, run.getCycleNo(),
                point.getTsMs(), point.getSeq(), next.type()));
    }
}
