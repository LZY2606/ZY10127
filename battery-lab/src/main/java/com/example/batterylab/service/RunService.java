package com.example.batterylab.service;

import com.example.batterylab.domain.*;
import com.example.batterylab.repo.*;
import com.example.batterylab.sim.SimState;
import com.example.batterylab.sim.TickResult;
import com.example.batterylab.sim.VirtualDevice;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Iterator;

/**
 * Application service for the run life-cycle and for driving the virtual device.
 *
 * Recovery semantics after a process restart (see {@link com.example.batterylab.config.StartupRecovery}):
 *  - RUNNING runs could not have cleanly stopped, so they are parked to PAUSED and flagged;
 *  - PAUSED runs keep their virtual clock, confirmed points, step windows and simulator
 *    state and may be resumed;
 *  - COMPLETED / CANCELLED / DEVICE_FAULT are terminal for ever: every control call on
 *    them is refused, they are never resurrected.
 */
@Service
public class RunService {

    private final RunRepository runRepository;
    private final ProtocolVersionRepository protocolRepository;
    private final DeviceProfileRepository deviceProfileRepository;
    private final RuleVersionRepository ruleRepository;
    private final SampleConflictRepository conflictRepository;
    private final IngestionService ingestionService;
    private final ObjectMapper objectMapper;

    public RunService(RunRepository runRepository,
                      ProtocolVersionRepository protocolRepository,
                      DeviceProfileRepository deviceProfileRepository,
                      RuleVersionRepository ruleRepository,
                      SampleConflictRepository conflictRepository,
                      IngestionService ingestionService,
                      ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.protocolRepository = protocolRepository;
        this.deviceProfileRepository = deviceProfileRepository;
        this.ruleRepository = ruleRepository;
        this.conflictRepository = conflictRepository;
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Run createRun(String protocolName, String protocolVersion,
                         String deviceSerial, String profileName, String deviceProfileVersion,
                         String ruleName, String ruleVersion) {
        ProtocolVersion protocol = protocolRepository
                .findByNameAndVersion(protocolName, protocolVersion)
                .orElseThrow(() -> new IllegalArgumentException("unknown protocol version"));
        DeviceProfile profile = deviceProfileRepository
                .findByNameAndVersion(profileName, deviceProfileVersion)
                .orElseThrow(() -> new IllegalArgumentException("unknown device profile"));
        RuleVersion rule = ruleRepository.findByNameAndVersion(ruleName, ruleVersion)
                .orElseThrow(() -> new IllegalArgumentException("unknown rule version"));

        Run run = new Run(deviceSerial, protocol.getId(), profile.getId(), rule.getId(),
                protocol.getBodyJson(), System.currentTimeMillis());
        try {
            run.setSimulatorStateJson(objectMapper.writeValueAsString(SimState.initial()));
        } catch (Exception e) {
            throw new IllegalStateException("cannot store simulator state", e);
        }
        return runRepository.save(run);
    }

    @Transactional
    public Run pause(long runId) {
        Run run = requireLive(runId);
        if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.CREATED) {
            throw new IllegalStateException("only a running run can be paused");
        }
        run.setStatus(RunStatus.PAUSED);
        run.setInterruptReason(InterruptReason.PAUSED);
        return runRepository.save(run);
    }

    @Transactional
    public Run resume(long runId) {
        Run run = require(runId);
        if (run.isTerminal()) {
            throw new IllegalStateException("terminal run " + run.getStatus() + " cannot be resumed");
        }
        if (run.getStatus() != RunStatus.PAUSED) {
            throw new IllegalStateException("only a paused run can be resumed");
        }
        run.setStatus(RunStatus.RUNNING);
        run.setInterruptReason(InterruptReason.NORMAL);
        return runRepository.save(run);
    }

    @Transactional
    public Run cancel(long runId) {
        Run run = require(runId);
        if (run.isTerminal()) {
            throw new IllegalStateException("run already terminal: " + run.getStatus());
        }
        run.setStatus(RunStatus.CANCELLED);
        run.setInterruptReason(InterruptReason.CANCELLED);
        return runRepository.save(run);
    }

    @Transactional
    public Run deviceFault(long runId) {
        Run run = requireLive(runId);
        run.setStatus(RunStatus.DEVICE_FAULT);
        run.setInterruptReason(InterruptReason.DEVICE_FAULT);
        run.setLinkState(LinkState.OFFLINE);
        return runRepository.save(run);
    }

    @Transactional
    public Run setLink(long runId, boolean online) {
        Run run = require(runId);
        run.setLinkState(online ? LinkState.ONLINE : LinkState.OFFLINE);
        SimState state = readState(run);
        state.online = online;
        writeStateInto(run, state);
        return runRepository.save(run);
    }

    /** Makes the next successful confirmation lose its acknowledgement (retry test hook). */
    @Transactional
    public void armLostAcknowledgement(long runId) {
        Run run = require(runId);
        SimState state = readState(run);
        state.loseNextAck = true;
        writeStateInto(run, state);
        runRepository.save(run);
    }

    /**
     * Advances the virtual device by tickMs and confirms every report it produces.
     * Offline reports are buffered on the device and replayed on reconnect; a lost
     * acknowledgement makes the device retry with the identical seq+payload.
     */
    /**
     * Advances the virtual device by tickMs and confirms reports one at a time, oldest
     * first, exactly like a serial link. Offline points are buffered and replayed on
     * reconnect; a lost acknowledgement stops transmission at that point and leaves it
     * pending for retry (still confirmed in the lab, so it is never counted twice).
     */
    @Transactional
    public TickResult tick(long runId, long tickMs) {
        Run run = require(runId);
        if (run.getStatus() == RunStatus.PAUSED || run.isTerminal()) {
            throw new IllegalStateException("cannot tick a " + run.getStatus() + " run");
        }
        ProtocolModel protocol = parseProtocol(run);
        SimState state = readState(run);
        TickResult result = new TickResult();
        boolean blocked = conflictRepository
                .existsByRunIdAndStatus(runId, ConflictStatus.OPEN);

        if (state.online && !state.pending.isEmpty() && !blocked) {
            deliver(run, state, result);
            if (!state.online || result.blockedByConflict) {
                // A lost ack (or conflict) stopped transmission during this tick; the
                // device does not keep sampling behind a link it believes is down.
                finishTick(run, state, result);
                return result;
            }
        }

        long targetT = state.t + tickMs;
        int guard = 0;
        while (state.online && state.pending.isEmpty() && !state.finished && !blocked
                && state.t < targetT && guard++ < 100_000) {
            int before = state.pending.size();
            VirtualDevice.advance(state, protocol, Math.min(targetT, state.t + VirtualDevice.GRID_MS));
            result.generated += state.pending.size() - before;
            if (state.pending.size() > before) {
                deliver(run, state, result);
                if (!state.online || result.blockedByConflict) {
                    finishTick(run, state, result);
                    return result;
                }
            }
            blocked = conflictRepository.existsByRunIdAndStatus(runId, ConflictStatus.OPEN);
        }
        if (!state.online) {
            // Offline: keep producing the full backlog for the requested virtual time.
            int offlineGuard = 0;
            while (state.t < targetT && !state.finished && offlineGuard++ < 100_000) {
                int before = state.pending.size();
                VirtualDevice.advance(state, protocol, targetT);
                if (state.pending.size() == before) {
                    break;
                }
                result.generated++;
            }
            result.buffered = state.pending.size();
        } else if (blocked) {
            result.blockedByConflict = true;
            result.buffered = state.pending.size();
        }

        finishTick(run, state, result);
        return result;
    }

    private void finishTick(Run run, SimState state, TickResult result) {
        state.finished = state.finished || run.getStatus() == RunStatus.COMPLETED;
        writeStateInto(run, state);
        runRepository.save(run);
        result.completed = run.getStatus() == RunStatus.COMPLETED;
        result.blockedByConflict = result.blockedByConflict
                || conflictRepository.existsByRunIdAndStatus(run.getId(), ConflictStatus.OPEN);
    }

    /**
     * Sends the device backlog oldest-first. Returns when the backlog drains, an open
     * conflict blocks the channel, or a lost acknowledgement stops transmission.
     */
    private void deliver(Run run, SimState state, TickResult result) {
        Iterator<SampleReport> it = state.pending.iterator();
        while (it.hasNext()) {
            SampleReport report = it.next();
            IngestOutcome outcome = ingestionService.confirm(run.getId(), report);
            result.outcomes.add(outcome);
            switch (outcome.kind()) {
                case CONFIRMED -> {
                    state.lastAckedSeq = report.seq();
                    if (state.loseNextAck) {
                        state.loseNextAck = false;
                        // Keep this point for retry and stop transmitting the batch.
                        result.buffered = state.pending.size();
                        state.online = false;
                        run.setLinkState(LinkState.OFFLINE);
                        return;
                    }
                    it.remove();
                    result.delivered++;
                }
                case DUPLICATE_IGNORED -> {
                    it.remove();
                    result.delivered++;
                }
                case CONFLICT, REFUSED_BLOCKED -> {
                    result.blockedByConflict = true;
                    result.buffered = state.pending.size();
                    return;
                }
                case REFUSED_TERMINAL -> {
                    it.remove();
                    state.finished = true;
                    return;
                }
            }
        }
    }

    private Run require(long runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("unknown run " + runId));
    }

    private Run requireLive(long runId) {
        Run run = require(runId);
        if (run.isTerminal()) {
            throw new IllegalStateException("terminal run cannot change state: " + run.getStatus());
        }
        return run;
    }

    private ProtocolModel parseProtocol(Run run) {
        try {
            return ProtocolModel.parse(objectMapper.readTree(run.getProtocolSnapshotJson()));
        } catch (Exception e) {
            throw new IllegalStateException("stored protocol unreadable", e);
        }
    }

    private SimState readState(Run run) {
        try {
            if (run.getSimulatorStateJson() == null) {
                SimState s = SimState.initial();
                initLoopCounters(s, run);
                return s;
            }
            SimState state = objectMapper.readValue(run.getSimulatorStateJson(), SimState.class);
            initLoopCounters(state, run);
            return state;
        } catch (Exception e) {
            throw new IllegalStateException("cannot read simulator state", e);
        }
    }

    private void initLoopCounters(SimState state, Run run) {
        ProtocolModel protocol = parseProtocol(run);
        for (int i = 0; i < protocol.steps().size(); i++) {
            if (protocol.steps().get(i).type() == StepType.LOOP) {
                state.loopCounters.putIfAbsent(i,
                        Math.max(0, protocol.steps().get(i).repetitions() - 1));
            }
        }
    }

    private void writeStateInto(Run run, SimState state) {
        try {
            run.setSimulatorStateJson(objectMapper.writeValueAsString(state));
        } catch (Exception e) {
            throw new IllegalStateException("cannot store simulator state", e);
        }
    }

}
