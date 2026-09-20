package com.example.batterylab.domain;

import jakarta.persistence.*;

/** One cycle row of one derived version: capacity, coulombic efficiency and interrupt. */
@Entity
@Table(name = "cycle_result",
        indexes = @Index(name = "ix_cycle_version", columnList = "derivedVersionId,cycleNo"))
public class CycleResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long derivedVersionId;

    @Column(nullable = false)
    private int cycleNo;

    @Column(nullable = false)
    private long startTsMs;
    @Column(nullable = false)
    private long endTsMs;
    private Long endPointId;

    /** Charge throughput, micro-Ah (signed integration of positive current). */
    @Column(nullable = false)
    private long chargeCapUah;

    /** Discharge throughput, micro-Ah (integration of negative current magnitude). */
    @Column(nullable = false)
    private long dischargeCapUah;

    /** Coulombic efficiency in basis points (9876 == 98.76 %). */
    @Column(nullable = false)
    private int coulombicEfficiencyBp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InterruptReason interruptReason;

    /** Number of excluded intervals intersecting this cycle. */
    @Column(nullable = false)
    private int excludedIntervalCount;

    /** Number of raw points inside this cycle. */
    @Column(nullable = false)
    private int rawPointCount;

    protected CycleResult() {
    }

    public CycleResult(long derivedVersionId, int cycleNo, long startTsMs, long endTsMs,
                       Long endPointId, long chargeCapUah, long dischargeCapUah,
                       int coulombicEfficiencyBp, InterruptReason interruptReason,
                       int excludedIntervalCount, int rawPointCount) {
        this.derivedVersionId = derivedVersionId;
        this.cycleNo = cycleNo;
        this.startTsMs = startTsMs;
        this.endTsMs = endTsMs;
        this.endPointId = endPointId;
        this.chargeCapUah = chargeCapUah;
        this.dischargeCapUah = dischargeCapUah;
        this.coulombicEfficiencyBp = coulombicEfficiencyBp;
        this.interruptReason = interruptReason;
        this.excludedIntervalCount = excludedIntervalCount;
        this.rawPointCount = rawPointCount;
    }

    public Long getId() {
        return id;
    }

    public long getDerivedVersionId() {
        return derivedVersionId;
    }

    public int getCycleNo() {
        return cycleNo;
    }

    public long getStartTsMs() {
        return startTsMs;
    }

    public long getEndTsMs() {
        return endTsMs;
    }

    public Long getEndPointId() {
        return endPointId;
    }

    public long getChargeCapUah() {
        return chargeCapUah;
    }

    public long getDischargeCapUah() {
        return dischargeCapUah;
    }

    public int getCoulombicEfficiencyBp() {
        return coulombicEfficiencyBp;
    }

    public InterruptReason getInterruptReason() {
        return interruptReason;
    }

    public int getExcludedIntervalCount() {
        return excludedIntervalCount;
    }

    public int getRawPointCount() {
        return rawPointCount;
    }
}
