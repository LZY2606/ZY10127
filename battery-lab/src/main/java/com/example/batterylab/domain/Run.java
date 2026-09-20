package com.example.batterylab.domain;

import jakarta.persistence.*;

/**
 * One execution of a protocol against one device serial, driven by a virtual clock.
 * The run freezes references to exact versions of protocol / device / rules so later
 * edits to any catalog never change an existing run.
 */
@Entity
@Table(name = "run")
public class Run {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String deviceSerial;

    @Column(nullable = false)
    private long protocolVersionId;

    @Column(nullable = false)
    private long deviceProfileId;

    @Column(nullable = false)
    private long ruleVersionId;

    /** Snapshot of the protocol JSON at start (immutable evidence). */
    @Lob
    @Column(nullable = false)
    private String protocolSnapshotJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status = RunStatus.CREATED;

    /** Virtual clock: timestamp of the last confirmed sample (run-relative ms). */
    @Column(nullable = false)
    private long virtualNowMs = 0L;

    /** Index of the step currently being executed in the flattened step list. */
    @Column(nullable = false)
    private int currentStepIndex = 0;

    /** Number of the cycle currently in progress (incremented when a charge step enters). */
    @Column(nullable = false)
    private int cycleNo = 0;

    /** Remaining LOOP jumps per LOOP step index, as JSON; survives process restart. */
    @Lob
    @Column
    private String loopCountersJson = "{}";

    /** Highest sample sequence confirmed for this device serial. */
    @Column(nullable = false)
    private long lastSeqConfirmed = -1L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LinkState linkState = LinkState.ONLINE;

    /** Simulator internal state as JSON, persisted so a restart continues deterministically. */
    @Lob
    @Column
    private String simulatorStateJson;

    @Column(nullable = false)
    private long createdAtMs;

    /** Reason the most recent cycle ended (NORMAL while a cycle is healthy). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InterruptReason interruptReason = InterruptReason.NORMAL;

    protected Run() {
    }

    public Run(String deviceSerial, long protocolVersionId, long deviceProfileId,
               long ruleVersionId, String protocolSnapshotJson, long createdAtMs) {
        this.deviceSerial = deviceSerial;
        this.protocolVersionId = protocolVersionId;
        this.deviceProfileId = deviceProfileId;
        this.ruleVersionId = ruleVersionId;
        this.protocolSnapshotJson = protocolSnapshotJson;
        this.createdAtMs = createdAtMs;
    }

    public Long getId() {
        return id;
    }

    public String getDeviceSerial() {
        return deviceSerial;
    }

    public long getProtocolVersionId() {
        return protocolVersionId;
    }

    public long getDeviceProfileId() {
        return deviceProfileId;
    }

    public long getRuleVersionId() {
        return ruleVersionId;
    }

    public String getProtocolSnapshotJson() {
        return protocolSnapshotJson;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public long getVirtualNowMs() {
        return virtualNowMs;
    }

    public void setVirtualNowMs(long virtualNowMs) {
        this.virtualNowMs = virtualNowMs;
    }

    public int getCurrentStepIndex() {
        return currentStepIndex;
    }

    public void setCurrentStepIndex(int currentStepIndex) {
        this.currentStepIndex = currentStepIndex;
    }

    public int getCycleNo() {
        return cycleNo;
    }

    public String getLoopCountersJson() {
        return loopCountersJson;
    }

    public void setLoopCountersJson(String loopCountersJson) {
        this.loopCountersJson = loopCountersJson;
    }

    public void setCycleNo(int cycleNo) {
        this.cycleNo = cycleNo;
    }

    public long getLastSeqConfirmed() {
        return lastSeqConfirmed;
    }

    public void setLastSeqConfirmed(long lastSeqConfirmed) {
        this.lastSeqConfirmed = lastSeqConfirmed;
    }

    public LinkState getLinkState() {
        return linkState;
    }

    public void setLinkState(LinkState linkState) {
        this.linkState = linkState;
    }

    public String getSimulatorStateJson() {
        return simulatorStateJson;
    }

    public void setSimulatorStateJson(String simulatorStateJson) {
        this.simulatorStateJson = simulatorStateJson;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public InterruptReason getInterruptReason() {
        return interruptReason;
    }

    public void setInterruptReason(InterruptReason interruptReason) {
        this.interruptReason = interruptReason;
    }

    public boolean isTerminal() {
        return status == RunStatus.COMPLETED
                || status == RunStatus.CANCELLED
                || status == RunStatus.DEVICE_FAULT;
    }
}
