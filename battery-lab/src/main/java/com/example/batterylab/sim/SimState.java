package com.example.batterylab.sim;

import com.example.batterylab.service.SampleReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, serializable state of the virtual device. Everything needed to continue
 * after a link drop, device restart or process restart lives here and is persisted with
 * the run - there is no wall-clock or random input anywhere.
 */
public class SimState {

    /** Device virtual clock (ms since its run started); moves only via emitted samples. */
    public long t;
    /** Next device sequence to assign. */
    public long nextSeq;
    /** Protocol step the device currently models. */
    public int stepIndex;
    /** Virtual time the current phase started. */
    public long phaseEnterT;
    /** Measured voltage when the current phase started (exact, integer mV). */
    public int phaseEnterMv;
    /** Target voltage for a REST relaxation phase. */
    public int restTargetMv;
    /** Voltage of the most recently produced report (exact, integer mV). */
    public int lastProducedMv = 3300;
    /** 1-based cycle the device is modelling. */
    public int cycleNo = 1;
    /** Remiving LOOP jumps per LOOP step index. */
    public Map<Integer, Integer> loopCounters = new HashMap<>();
    /** Reports produced but not yet acknowledged (offline window, blocked or lost ack). */
    public List<SampleReport> pending = new ArrayList<>();
    /** Highest seq acknowledged by the lab service. */
    public long lastAckedSeq = -1L;
    public boolean online = true;
    public boolean finished = false;
    /** When true, the next successful confirmation behaves as if its ack was lost. */
    public boolean loseNextAck = false;
    /** Number of reports intentionally held back beyond the offline window (fault script). */
    public int holdReports = 0;

    public static SimState initial() {
        SimState s = new SimState();
        s.t = 0L;
        s.nextSeq = 0L;
        s.stepIndex = 0;
        s.phaseEnterT = 0L;
        s.phaseEnterMv = 3300;
        s.lastProducedMv = 3300;
        s.restTargetMv = 4150;
        s.cycleNo = 1;
        return s;
    }
}
